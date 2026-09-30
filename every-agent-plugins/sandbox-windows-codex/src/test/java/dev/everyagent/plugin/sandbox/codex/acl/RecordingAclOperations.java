package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试假件：记录全部 ACE 调用（跨平台单测用；对齐 codex acl_tests 的注入式验证思路）。
 * 可按路径注入失败以驱动回滚分支。
 */
class RecordingAclOperations implements AclOperations {

    record Call(String op, Path path, List<String> sids) {
        @Override
        public String toString() {
            return op + "(" + path + ", " + sids + ")";
        }
    }

    final List<Call> calls = new ArrayList<>();
    final Map<String, Boolean> denyReadAdded = new ConcurrentHashMap<>();
    final Map<String, Boolean> denyWriteAdded = new ConcurrentHashMap<>();
    /** 失败注入：路径词法键 → 抛 IOException 的操作名。 */
    final Map<String, String> failures = new ConcurrentHashMap<>();
    boolean maskAllows;

    private String key(Path path) {
        return DenyReadPlanner.lexicalPathKey(path);
    }

    void failOn(Path path, String op) {
        failures.put(key(path), op);
    }

    private void maybeFail(Path path, String op) throws IOException {
        if (op.equals(failures.get(key(path)))) {
            throw new IOException("injected failure for " + op + " on " + path);
        }
    }

    @Override
    public boolean addDenyReadAce(Path path, String sid) throws IOException {
        calls.add(new Call("denyRead", path, List.of(sid)));
        maybeFail(path, "denyRead");
        return denyReadAdded.put(key(path), true) == null;
    }

    @Override
    public boolean addDenyWriteAce(Path path, String sid) throws IOException {
        calls.add(new Call("denyWrite", path, List.of(sid)));
        maybeFail(path, "denyWrite");
        return denyWriteAdded.put(key(path) + "|" + sid, true) == null;
    }

    @Override
    public void revokeAce(Path path, String sid) throws IOException {
        calls.add(new Call("revoke", path, List.of(sid)));
        maybeFail(path, "revoke");
    }

    @Override
    public boolean ensureAllowWriteAces(Path path, List<String> sids) throws IOException {
        calls.add(new Call("allowWrite", path, sids));
        maybeFail(path, "allowWrite");
        return true;
    }

    @Override
    public boolean ensureReadExecuteAces(Path path, List<String> sids) throws IOException {
        calls.add(new Call("readExec", path, sids));
        maybeFail(path, "readExec");
        return true;
    }

    @Override
    public boolean pathWriteAcesNeedRefresh(Path path, List<String> sids) {
        calls.add(new Call("needRefresh", path, sids));
        return true;
    }

    @Override
    public boolean pathMaskAllows(Path path, List<String> sids, int mask, boolean requireAllBits) {
        calls.add(new Call("maskAllows", path, sids));
        return maskAllows;
    }
}
