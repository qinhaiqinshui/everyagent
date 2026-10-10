package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.OaIdl;
import com.sun.jna.platform.win32.OaIdl.DISPID;
import com.sun.jna.platform.win32.OaIdl.EXCEPINFO;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.Variant.VARIANT;
import com.sun.jna.platform.win32.WinDef.LCID;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.COM.Dispatch;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.HashMap;
import java.util.Map;

import dev.everyagent.plugin.sandbox.codex.setup.HelperLog;

/**
 * Windows 防火墙 COM 调用面（jna-platform 类型化实现）。
 *
 * <p><b>历史教训（2026-10-03 排障结论）</b>：初版为避开 vtable 槽位猜测，手工用
 * {@code Function.invokeInt(Object[])} + 裸 {@code Memory} 拼 VARIANT/DISPPARAMS。
 * 实测同一调用随机返回 E_INVALIDARG / TYPE_E_BADMODULEKIND 等（结果 VARIANT 含
 * 垃圾值）——4 字节标量落入 x64 8 字节栈槽时高位未定义，属于封送层 UB，无法修复。
 *
 * <p>现行实现完全复用 jna-platform 的类型化 COM 栈（设计文档 §3「复用
 * jna-platform(不重映射)」原则的回归）：{@link Dispatch#GetIDsOfNames} /
 * {@link Dispatch#Invoke}（{@code _invokeNativeObject} 槽位调用，DISPID/REFIID/
 * LCID/WORD/DISPPARAMS.ByReference/VARIANT.ByReference 全类型化）+
 * {@link VARIANT}/{@link OleAuto.DISPPARAMS} 结构体字段由 JNA 自动同步。
 * 实测（ComTest4）：propget/propput/方法调用 ×5 全部确定成功。
 *
 * <p>对外 API 与初版签名兼容（Pointer 进出），FirewallInstaller 无需改动；
 * DISPID 仍按 (对象,名字) 缓存。BSTR 生命周期由 VARIANT 构造器托管
 * （SysAllocString 的所有权移交，{@code VARIANT.clear()} 统一释放）。
 */
public final class NetFwCom {

    /** DISPATCH_METHOD / PROPERTYGET / PROPERTYPUT。 */
    public static final int DISPATCH_METHOD = 0x1;
    public static final int DISPATCH_PROPERTYGET = 0x2;
    public static final int DISPATCH_PROPERTYPUT = 0x4;

    /** VT_EMPTY / VT_I4 / VT_BOOL / VT_BSTR / VT_DISPATCH（读结果判定用）。 */
    public static final int VT_EMPTY = 0;
    public static final int VT_I4 = 3;
    public static final int VT_BOOL = 6;
    public static final int VT_BSTR = 8;
    public static final int VT_DISPATCH = 9;

    /** IID_IDispatch（CoCreateInstance 请求 IDispatch 视图——firewall COM 均为双接口）。 */
    public static final Guid.GUID IID_IDISPATCH =
            guid("00020400-0000-0000-C000-000000000046");

    // ---- CLSID（netfw.h 平台常量） ----

    /** CLSID_NetFwPolicy2。 */
    public static final Guid.GUID CLSID_NET_FW_POLICY2 =
            guid("E2B3C97F-6AE1-41AC-817A-F6F92166D7DD");
    /** IID_INetFwPolicy2（保留常量供诊断；调用一律走 IDispatch）。 */
    public static final Guid.GUID IID_INET_FW_POLICY2 =
            guid("98325047-C671-4174-8D81-DCC13A14F44C");
    /** CLSID_NetFwRule。 */
    public static final Guid.GUID CLSID_NET_FW_RULE =
            guid("2C5BC43E-3369-4C33-AB0C-BE9469677AF4");

    /** LOCALE_USER_DEFAULT。 */
    private static final int LOCALE_USER_DEFAULT = 0x0400;

    /** (COM 对象指针, 方法名) → DISPID 缓存（同一对象反复 Invoke 免 GetIDsOfNames）。 */
    private static final Map<Pointer, Map<String, Integer>> DISPID_CACHE = new HashMap<>();

    private NetFwCom() {
    }

    /** COM apartment 守卫：初始化 STA（容忍 RPC_E_CHANGED_MODE 只清自己的引用计数）。 */
    public static final class Apartment implements AutoCloseable {
        private final boolean initialized;

        private Apartment(boolean initialized) {
            this.initialized = initialized;
        }

        public static Apartment initialize() {
            int hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
                    .intValue();
            HelperLog.log("COM CoInitializeEx(APARTMENTTHREADED) hr=" + HelperLog.hex(hr));
            if (hr != 0 && hr != WinErr.RPC_E_CHANGED_MODE) {
                throw new IllegalStateException("CoInitializeEx failed: 0x"
                        + Integer.toUnsignedString(hr, 16));
            }
            return new Apartment(hr == 0);
        }

        @Override
        public void close() {
            if (initialized) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    /** CoCreateInstance（in-proc，请求 IDispatch；失败抛含 HRESULT）。 */
    public static Pointer coCreateInstance(Guid.GUID clsid, Guid.GUID iid) {
        HelperLog.log("COM CoCreateInstance clsid=" + clsid.toGuidString()
                + " iid=" + iid.toGuidString());
        PointerByReference out = new PointerByReference();
        int hr = Ole32.INSTANCE.CoCreateInstance(clsid, null, 1 /* CLSCTX_INPROC_SERVER */,
                iid, out).intValue();
        HelperLog.log("COM CoCreateInstance hr=" + HelperLog.hex(hr)
                + " obj=" + out.getValue());
        if (hr != 0) {
            throw new IllegalStateException("CoCreateInstance failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        return out.getValue();
    }

    /** IUnknown::Release（判空）。 */
    public static void release(Pointer comObj) {
        if (comObj != null) {
            new Dispatch(comObj).Release();
        }
    }

    /** GetIDsOfNames（按名取 DISPID；每对象缓存）。 */
    public static int dispid(Pointer dispatch, String name) {
        Map<String, Integer> cache = DISPID_CACHE.computeIfAbsent(dispatch,
                k -> new HashMap<>());
        Integer cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        OaIdl.DISPIDByReference out = new OaIdl.DISPIDByReference();
        int hr = new Dispatch(dispatch).GetIDsOfNames(
                new Guid.REFIID(Guid.IID_NULL),
                new com.sun.jna.WString[] { new com.sun.jna.WString(name) },
                1, new LCID(LOCALE_USER_DEFAULT), out).intValue();
        if (hr != 0) {
            throw new IllegalStateException("GetIDsOfNames(" + name + ") failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        int dispid = out.getValue().intValue();
        cache.put(name, dispid);
        return dispid;
    }

    /** IDispatch::Invoke（类型化底层；DISPPARAMS 与结果 VARIANT 由 JNA 同步）。 */
    private static VARIANT invoke(Pointer dispatch, String name, int flags,
            OleAuto.DISPPARAMS.ByReference dispParams, boolean wantResult) {
        VARIANT.ByReference result = new VARIANT.ByReference();
        int hr = new Dispatch(dispatch).Invoke(
                new DISPID(dispid(dispatch, name)),
                new Guid.REFIID(Guid.IID_NULL),
                new LCID(LOCALE_USER_DEFAULT),
                new WORD(flags),
                dispParams,
                wantResult ? result : new VARIANT.ByReference(),
                new EXCEPINFO.ByReference(),
                new IntByReference()).intValue();
        HelperLog.log("COM Invoke name=" + name + " flags=0x" + Integer.toHexString(flags)
                + " hr=" + HelperLog.hex(hr)
                + (wantResult ? " vt=" + result.getVarType() : ""));
        if (hr != 0) {
            throw new IllegalStateException("Invoke " + name + " (flags=0x"
                    + Integer.toHexString(flags) + ") failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        return result;
    }

    /** 空参数 DISPPARAMS（propget 用；结构体零值即无参）。 */
    public static OleAuto.DISPPARAMS.ByReference emptyDispParams() {
        return new OleAuto.DISPPARAMS.ByReference();
    }

    // ---- propput（VARIANT 构造器托管 BSTR/类型；DISPID_PROPERTYPUT 命名参数） ----

    /** propput（BSTR 值）。 */
    public static void putBstr(Pointer dispatch, String prop, String value) {
        VARIANT varg = new VARIANT(
                OleAuto.INSTANCE.SysAllocString(value));
        invokePut(dispatch, prop, varg);
    }

    /** propput（I4 值：Protocol/Direction/Profiles/Action 等 IDL long 属性）。 */
    public static void putI4(Pointer dispatch, String prop, int value) {
        invokePut(dispatch, prop, new VARIANT(value));
    }

    /** propput（BOOL 值：Enabled）。 */
    public static void putBool(Pointer dispatch, String prop, boolean value) {
        invokePut(dispatch, prop, new VARIANT(value));
    }

    private static void invokePut(Pointer dispatch, String prop, VARIANT varg) {
        OleAuto.DISPPARAMS.ByReference dp = new OleAuto.DISPPARAMS.ByReference();
        dp.setArgs(new VARIANT[] { varg });
        dp.setRgdispidNamedArgs(new DISPID[] { OaIdl.DISPID_PROPERTYPUT });
        invoke(dispatch, prop, DISPATCH_PROPERTYPUT, dp, false);
        varg.clear();
    }

    // ---- propget（结果 VARIANT 读值） ----

    /** propget → int（LocalPolicyModifyState 等 VT_I4 属性）。 */
    public static int getInt(Pointer dispatch, String prop) {
        VARIANT v = invoke(dispatch, prop, DISPATCH_PROPERTYGET,
                emptyDispParams(), true);
        if (v.getVarType().intValue() != VT_I4) {
            throw new IllegalStateException("propget " + prop + " returned vt="
                    + v.getVarType());
        }
        return v.intValue();
    }

    /** propget → String（LocalUserAuthorizedList 等 VT_BSTR 属性）。 */
    public static String getString(Pointer dispatch, String prop) {
        VARIANT v = invoke(dispatch, prop, DISPATCH_PROPERTYGET,
                emptyDispParams(), true);
        if (v.getVarType().intValue() != VT_BSTR) {
            throw new IllegalStateException("propget " + prop + " returned vt="
                    + v.getVarType());
        }
        Object value = v.getValue();
        return value == null ? null : value.toString();
    }

    /** propget → IDispatch*（Rules 属性；返回自持引用，调用方负责 Release）。 */
    public static Pointer getDispatch(Pointer dispatch, String prop) {
        VARIANT v = invoke(dispatch, prop, DISPATCH_PROPERTYGET,
                emptyDispParams(), true);
        if (v.getVarType().intValue() != VT_DISPATCH) {
            throw new IllegalStateException("propget " + prop + " returned vt="
                    + v.getVarType());
        }
        Object value = v.getValue();
        if (value instanceof Pointer p) {
            return p;
        }
        if (value instanceof Dispatch d) {
            return d.getPointer();
        }
        throw new IllegalStateException("propget " + prop + " unexpected value " + value);
    }

    // ---- 方法调用 ----

    /** 方法调用：单 IDispatch* 实参（Rules::Add(rule)）。 */
    public static void callWithDispatchArg(Pointer dispatch, String method, Pointer arg) {
        OleAuto.DISPPARAMS.ByReference dp = new OleAuto.DISPPARAMS.ByReference();
        VARIANT varg = new VARIANT(new Dispatch(arg));
        dp.setArgs(new VARIANT[] { varg });
        invoke(dispatch, method, DISPATCH_METHOD, dp, false);
        varg.clear();
    }

    /** 方法调用：单 BSTR 实参 → IDispatch*（Rules::Item(name)；不存在返回 null）。 */
    public static Pointer callWithStringArgReturningDispatch(Pointer dispatch, String method,
            String arg) {
        OleAuto.DISPPARAMS.ByReference dp = new OleAuto.DISPPARAMS.ByReference();
        VARIANT varg = new VARIANT(OleAuto.INSTANCE.SysAllocString(arg));
        dp.setArgs(new VARIANT[] { varg });
        VARIANT v;
        try {
            v = invoke(dispatch, method, DISPATCH_METHOD, dp, true);
        } catch (IllegalStateException e) {
            return null; // 不存在（HRESULT 错误）——调用方按 not-present 处理
        } finally {
            varg.clear();
        }
        if (v.getVarType().intValue() == VT_EMPTY) {
            return null;
        }
        if (v.getVarType().intValue() != VT_DISPATCH) {
            throw new IllegalStateException(method + " returned vt=" + v.getVarType());
        }
        Object value = v.getValue();
        if (value instanceof Pointer p) {
            return p;
        }
        if (value instanceof Dispatch d) {
            return d.getPointer();
        }
        throw new IllegalStateException(method + " unexpected value " + value);
    }

    /** 方法调用：单 BSTR 实参、无返回（Rules::Remove(name)）；失败返回非 0。 */
    public static int callWithStringArg(Pointer dispatch, String method, String arg) {
        OleAuto.DISPPARAMS.ByReference dp = new OleAuto.DISPPARAMS.ByReference();
        VARIANT varg = new VARIANT(OleAuto.INSTANCE.SysAllocString(arg));
        dp.setArgs(new VARIANT[] { varg });
        try {
            invoke(dispatch, method, DISPATCH_METHOD, dp, false);
            return 0;
        } catch (IllegalStateException e) {
            // 调用方按 HRESULT 语义判定；具体码已在 HelperLog 留痕
            return -1;
        } finally {
            varg.clear();
        }
    }

    /** 解析 "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" 为 GUID（Windows 小序布局）。 */
    public static Guid.GUID guid(String s) {
        return new Guid.GUID(s);
    }
}
