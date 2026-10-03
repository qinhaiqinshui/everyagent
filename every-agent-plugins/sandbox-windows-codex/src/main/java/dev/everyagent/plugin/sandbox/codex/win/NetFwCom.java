package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.HashMap;
import java.util.Map;

/**
 * Windows 防火墙 COM 的手工 IDispatch 调用面（设计文档 §3「COM 难点决策」；
 * 本插件新增文件——JNA 无内建 COM）。
 *
 * <p>对齐 codex setup_provisioning/firewall.rs 的调用面（CoInitializeEx
 * APARTMENTTHREADED、容忍 RPC_E_CHANGED_MODE、CoCreateInstance、BSTR 管理），
 * 但方法/属性解析走 {@code IDispatch::GetIDsOfNames + Invoke}（按名解析）而非
 * 各接口自有方法的 vtable 槽位直调：INetFwRule3 继承链（INetFwRule 属性对 +
 * INetFwRule2 + INetFwRule3）的 IDL 声明序无法在非 Windows 环境核对，
 * IDispatch 是双接口的契约层（槽位 3-6 固定：GetTypeInfoCount/GetTypeInfo/
 * GetIDsOfNames/Invoke），零槽位猜测、零臆造——与「宁拒不裸」一致。
 *
 * <p>x64 下调用约定统一（stdcall 与 cdecl 同构）；x86 下以 ALT_CONVENTION 对齐。
 * VARIANT 按头部使用（vt@0 + union@8；VT_BSTR/VT_I4/VT_BOOL/VT_DISPATCH 均落在
 * 前 16 字节）。VARIANT 生命周期：结果读值后 {@link #clearVariant}。
 */
public final class NetFwCom {

    /** IDispatch 契约槽位（IUnknown 3 个之后，顺序由 IDispatch 定义固定）。 */
    public static final int SLOT_GET_IDS_OF_NAMES = 5;
    public static final int SLOT_INVOKE = 6;

    /** IUnknown::QueryInterface / Release。 */
    public static final int SLOT_QUERY_INTERFACE = 0;
    public static final int SLOT_RELEASE = 2;

    /** DISPATCH_METHOD / PROPERTYGET / PROPERTYPUT。 */
    public static final int DISPATCH_METHOD = 0x1;
    public static final int DISPATCH_PROPERTYGET = 0x2;
    public static final int DISPATCH_PROPERTYPUT = 0x4;
    /** DISPID_PROPERTYPUT（propput 的具名参数标记）。 */
    public static final int DISPID_PROPERTYPUT = 0;

    /** VT_EMPTY / VT_I4 / VT_BOOL / VT_BSTR / VT_DISPATCH。 */
    public static final int VT_EMPTY = 0;
    public static final int VT_I4 = 3;
    public static final int VT_BOOL = 6;
    public static final int VT_BSTR = 8;
    public static final int VT_DISPATCH = 9;

    /** IID_NULL（GetIDsOfNames/Invoke 的 riid 惯例值）。 */
    public static final Guid.GUID IID_NULL = guid("00000000-0000-0000-0000-000000000000");
    /** IID_IDispatch（QI/CoCreateInstance 取 IDispatch 视图）。 */
    public static final Guid.GUID IID_IDISPATCH = guid("00020400-0000-0000-C000-000000000046");

    // ---- CLSID/IID（netfw.h 平台常量，平台所有；与 firewall.rs 引用一致） ----

    /** CLSID_NetFwPolicy2。 */
    public static final Guid.GUID CLSID_NET_FW_POLICY2 =
            guid("E2B3C97F-6AE1-41AC-817A-F6F92166D7DD");
    /** IID_INetFwPolicy2。 */
    public static final Guid.GUID IID_INET_FW_POLICY2 =
            guid("98325047-C671-4174-8D81-DCC13A14F44C");
    /** CLSID_NetFwRule。 */
    public static final Guid.GUID CLSID_NET_FW_RULE =
            guid("2C5BC43E-3369-4C33-AB0C-BE9469677AF4");

    /** LOCALE_USER_DEFAULT（lcid 惯例值）。 */
    private static final int LOCALE_USER_DEFAULT = 0x0400;

    private static final int VARIANT_SIZE = 24; // x64 VARIANT 上限（含 DECIMAL 尾部）
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

    /** CoCreateInstance（in-proc；失败抛 IllegalStateException 含 HRESULT）。 */
    public static Pointer coCreateInstance(Guid.GUID clsid, Guid.GUID iid) {
        PointerByReference out = new PointerByReference();
        int hr = Ole32.INSTANCE.CoCreateInstance(clsid, null, 1 /* CLSCTX_INPROC_SERVER */,
                iid, out).intValue();
        if (hr != 0) {
            throw new IllegalStateException("CoCreateInstance failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        return out.getValue();
    }

    /** IUnknown::Release（判空）。 */
    public static void release(Pointer comObj) {
        if (comObj != null) {
            invokeInt(comObj, SLOT_RELEASE);
        }
    }

    /** vtable 直调（HRESULT；首个隐参 this=comObj；仅用于 IUnknown/IDispatch 契约槽）。
     * 对齐 JNA COMInvoker：用默认调用约定（非 ALT_CONVENTION），
     * 直接传 args（含 this 指针作为 args[0]）。 */
    public static int invokeInt(Pointer comObj, int slot, Object... args) {
        Pointer vtable = comObj.getPointer(0);
        Pointer fn = vtable.getPointer((long) slot * Native.POINTER_SIZE);
        Function function = Function.getFunction(fn);
        Object[] full = new Object[args.length + 1];
        full[0] = comObj;
        System.arraycopy(args, 0, full, 1, args.length);
        return function.invokeInt(full);
    }

    /** GetIDsOfNames（按名取 DISPID；每对象缓存）。 */
    public static int dispid(Pointer dispatch, String name) {
        Map<String, Integer> cache = DISPID_CACHE.computeIfAbsent(dispatch,
                k -> new HashMap<>());
        Integer cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        WTypes.BSTR nameBstr = OleAuto.INSTANCE.SysAllocString(name);
        Memory namesArray = new Memory(Native.POINTER_SIZE);
        namesArray.setPointer(0, nameBstr.getPointer());
        Memory riid = new Memory(16);
        IntByReference dispid = new IntByReference();
        try {
            int hr = invokeInt(dispatch, SLOT_GET_IDS_OF_NAMES, riid, namesArray, 1,
                    LOCALE_USER_DEFAULT, dispid);
            if (hr != 0) {
                throw new IllegalStateException("GetIDsOfNames(" + name + ") failed: 0x"
                        + Integer.toUnsignedString(hr, 16));
            }
        } finally {
            OleAuto.INSTANCE.SysFreeString(nameBstr);
        }
        cache.put(name, dispid.getValue());
        return dispid.getValue();
    }

    /** IDispatch::Invoke（底层）。 */
    private static int invoke(Pointer dispatch, String name, int flags,
            Memory dispParams, Memory varResult) {
        Memory riid = new Memory(16);
        Memory excepInfo = new Memory(64);
        IntByReference argErr = new IntByReference();
        return invokeInt(dispatch, SLOT_INVOKE, dispid(dispatch, name), riid,
                LOCALE_USER_DEFAULT, flags, dispParams, varResult, excepInfo, argErr);
    }

    /** 空参数 DISPPARAMS（清零结构体即无参）。 */
    public static Memory emptyDispParams() {
        return new Memory(32);
    }

    /** propput（BSTR 值）。 */
    public static void putBstr(Pointer dispatch, String prop, String value) {
        WTypes.BSTR bstr = OleAuto.INSTANCE.SysAllocString(value);
        try {
            putVariant(dispatch, prop, VT_BSTR, bstr.getPointer());
        } finally {
            // callee 已复制值，本侧立即释放
            OleAuto.INSTANCE.SysFreeString(bstr);
        }
    }

    /** propput（I4 值：Protocol/Direction/Profiles/Action 等 IDL long 属性）。 */
    public static void putI4(Pointer dispatch, String prop, int value) {
        Memory payload = new Memory(8);
        payload.setInt(0, value);
        putVariant(dispatch, prop, VT_I4, payload);
    }

    /** propput（BOOL 值：Enabled）。 */
    public static void putBool(Pointer dispatch, String prop, boolean value) {
        Memory payload = new Memory(8);
        payload.setShort(0, (short) (value ? -1 : 0)); // VARIANT_TRUE = 0xFFFF
        putVariant(dispatch, prop, VT_BOOL, payload);
    }

    private static void putVariant(Pointer dispatch, String prop, int vt, Pointer payload) {
        Memory dispParams = new Memory(32); // DISPPARAMS
        Memory varg = new Memory(VARIANT_SIZE);
        varg.setShort(0, (short) vt);
        varg.setPointer(8, payload);
        Memory namedArgs = new Memory(Native.POINTER_SIZE);
        namedArgs.setInt(0, DISPID_PROPERTYPUT);
        // DISPPARAMS layout (x64): rgvarg(0), rgdispidNamedArgs(8), cArgs(16), cNamedArgs(20)
        dispParams.setPointer(0, varg);                   // rgvarg → VARIANT 参数
        dispParams.setPointer(Native.POINTER_SIZE, namedArgs); // rgdispidNamedArgs → DISPID_PROPERTYPUT
        dispParams.setInt(Native.POINTER_SIZE * 2, 1);    // cArgs
        dispParams.setInt(Native.POINTER_SIZE * 2 + 4, 1); // cNamedArgs
        int hr = invoke(dispatch, prop, DISPATCH_PROPERTYPUT, dispParams, null);
        if (hr != 0) {
            throw new IllegalStateException("Invoke propput " + prop + " failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
    }

    /** propget → int（LocalPolicyModifyState 等 VT_I4 属性）。 */
    public static int getInt(Pointer dispatch, String prop) {
        Memory result = new Memory(VARIANT_SIZE);
        int hr = invoke(dispatch, prop, DISPATCH_PROPERTYGET, emptyDispParams(), result);
        if (hr != 0) {
            throw new IllegalStateException("Invoke propget " + prop + " failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        try {
            int vt = result.getShort(0) & 0xFFFF;
            if (vt != VT_I4) {
                throw new IllegalStateException("propget " + prop + " returned vt=" + vt);
            }
            return result.getInt(8);
        } finally {
            clearVariant(result);
        }
    }

    /** propget → String（LocalUserAuthorizedList 等 VT_BSTR 属性；复制后释放）。 */
    public static String getString(Pointer dispatch, String prop) {
        Memory result = new Memory(VARIANT_SIZE);
        int hr = invoke(dispatch, prop, DISPATCH_PROPERTYGET, emptyDispParams(), result);
        if (hr != 0) {
            throw new IllegalStateException("Invoke propget " + prop + " failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        try {
            int vt = result.getShort(0) & 0xFFFF;
            if (vt != VT_BSTR) {
                throw new IllegalStateException("propget " + prop + " returned vt=" + vt);
            }
            return new WTypes.BSTR(result.getPointer(8)).getValue();
        } finally {
            clearVariant(result);
        }
    }

    /** propget → IDispatch*（Rules 属性；返回自持引用，调用方负责 Release）。 */
    public static Pointer getDispatch(Pointer dispatch, String prop) {
        Memory result = new Memory(VARIANT_SIZE);
        int hr = invoke(dispatch, prop, DISPATCH_PROPERTYGET, emptyDispParams(), result);
        if (hr != 0) {
            throw new IllegalStateException("Invoke propget " + prop + " failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
        try {
            int vt = result.getShort(0) & 0xFFFF;
            if (vt != VT_DISPATCH) {
                throw new IllegalStateException("propget " + prop + " returned vt=" + vt);
            }
            Pointer obj = result.getPointer(8);
            PointerByReference keep = new PointerByReference();
            int qi = invokeInt(obj, SLOT_QUERY_INTERFACE, IID_IDISPATCH, keep);
            if (qi != 0) {
                throw new IllegalStateException("QI(IDispatch) on " + prop + " failed: 0x"
                        + Integer.toUnsignedString(qi, 16));
            }
            return keep.getValue();
        } finally {
            clearVariant(result);
        }
    }

    /** 方法调用：单 IDispatch* 实参（Rules::Add(rule)）。 */
    public static void callWithDispatchArg(Pointer dispatch, String method, Pointer arg) {
        Memory dispParams = new Memory(32);
        Memory varg = new Memory(VARIANT_SIZE);
        varg.setShort(0, (short) VT_DISPATCH);
        varg.setPointer(8, arg);
        // DISPPARAMS: rgvarg(0), rgdispidNamedArgs(8), cArgs(16), cNamedArgs(20)
        dispParams.setPointer(0, varg);               // rgvarg
        dispParams.setInt(Native.POINTER_SIZE * 2, 1);    // cArgs
        int hr = invoke(dispatch, method, DISPATCH_METHOD, dispParams, null);
        if (hr != 0) {
            throw new IllegalStateException("Invoke " + method + " failed: 0x"
                    + Integer.toUnsignedString(hr, 16));
        }
    }

    /** 方法调用：单 BSTR 实参 → IDispatch*（Rules::Item(name)；不存在返回 null）。 */
    public static Pointer callWithStringArgReturningDispatch(Pointer dispatch, String method,
            String arg) {
        Memory dispParams = new Memory(32);
        WTypes.BSTR bstr = OleAuto.INSTANCE.SysAllocString(arg);
        Memory varg = new Memory(VARIANT_SIZE);
        varg.setShort(0, (short) VT_BSTR);
        varg.setPointer(8, bstr.getPointer());
        // DISPPARAMS: rgvarg(0), rgdispidNamedArgs(8), cArgs(16), cNamedArgs(20)
        dispParams.setPointer(0, varg);
        dispParams.setInt(Native.POINTER_SIZE * 2, 1);
        Memory result = new Memory(VARIANT_SIZE);
        try {
            int hr = invoke(dispatch, method, DISPATCH_METHOD, dispParams, result);
            if (hr != 0) {
                return null; // 不存在（HRESULT 错误）——调用方按 not-present 处理
            }
            int vt = result.getShort(0) & 0xFFFF;
            if (vt == VT_EMPTY) {
                return null;
            }
            if (vt != VT_DISPATCH) {
                throw new IllegalStateException(method + " returned vt=" + vt);
            }
            Pointer obj = result.getPointer(8);
            PointerByReference keep = new PointerByReference();
            invokeInt(obj, SLOT_QUERY_INTERFACE, IID_IDISPATCH, keep);
            return keep.getValue();
        } finally {
            OleAuto.INSTANCE.SysFreeString(bstr);
            clearVariant(result);
        }
    }

    /** 方法调用：单 BSTR 实参、无返回（Rules::Remove(name)）→ HRESULT。 */
    public static int callWithStringArg(Pointer dispatch, String method, String arg) {
        Memory dispParams = new Memory(32);
        WTypes.BSTR bstr = OleAuto.INSTANCE.SysAllocString(arg);
        Memory varg = new Memory(VARIANT_SIZE);
        varg.setShort(0, (short) VT_BSTR);
        varg.setPointer(8, bstr.getPointer());
        // DISPPARAMS: rgvarg(0), rgdispidNamedArgs(8), cArgs(16), cNamedArgs(20)
        dispParams.setPointer(0, varg);
        dispParams.setInt(Native.POINTER_SIZE * 2, 1);
        try {
            return invoke(dispatch, method, DISPATCH_METHOD, dispParams, null);
        } finally {
            OleAuto.INSTANCE.SysFreeString(bstr);
        }
    }

    /** VARIANT 生命周期收尾（BSTR→SysFreeString、DISPATCH→Release、清零）。 */
    public static void clearVariant(Memory variant) {
        int vt = variant.getShort(0) & 0xFFFF;
        if (vt == VT_BSTR) {
            Pointer p = variant.getPointer(8);
            if (p != null) {
                OleAuto.INSTANCE.SysFreeString(new WTypes.BSTR(p));
            }
        } else if (vt == VT_DISPATCH) {
            Pointer p = variant.getPointer(8);
            if (p != null) {
                release(p);
            }
        }
        for (long off = 0; off < VARIANT_SIZE; off++) {
            variant.setByte(off, (byte) 0);
        }
    }

    /** 解析 "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" 为 GUID（Windows 小序布局）。 */
    public static Guid.GUID guid(String s) {
        return new Guid.GUID(s);
    }
}
