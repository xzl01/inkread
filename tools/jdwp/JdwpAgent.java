import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 通过 JDWP（adb forward tcp:<port> jdwp:<pid>）在设备进程内执行操作。
 * 设备是 userdebug 构建，大量系统应用可被 JDWP 附加。
 *
 * 用法:
 *   info <port>                打印 pid/uid/进程名
 *   props <port> <key>         读取系统属性
 *   exec <port> <command>      以目标进程 UID 执行 shell 命令并返回输出
 *   dump <port> <keyword>      列出已加载的类/方法中名称含关键词的条目
 */
public class JdwpAgent {
    private static VirtualMachine vm;
    private static ThreadReference thread;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); return; }
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        Throwable last = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                attach(port);
                try {
                    if (mode.equals("patchbool") || mode.equals("getbool") || mode.equals("setprop2")) {
                        execute(mode, args);
                    } else {
                        if (!suspendedThread()) throw new IllegalStateException("no suspend event");
                        execute(mode, args);
                    }
                    return;
                } finally {
                    try { vm.resume(); } catch (Throwable ignored) {}
                    try { vm.dispose(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                last = t;
                try { if (vm != null) vm.dispose(); } catch (Throwable ignored) {}
                Thread.sleep(400);
            }
        }
        System.out.println("ERR: " + (last == null ? "unknown" : last.toString()));
    }

    private static void execute(String mode, String[] args) throws Exception {
        if (mode.equals("info")) {
            System.out.println("pid=" + intCall("android.os.Process", "myPid"));
            System.out.println("uid=" + intCall("android.os.Process", "myUid"));
            System.out.println("name=" + strCall("android.app.ActivityThread", "currentProcessName"));
            System.out.println("pkg=" + strCall("android.app.ActivityThread", "currentPackageName"));
        } else if (mode.equals("props")) {
            for (int i = 2; i < args.length; i++) {
                System.out.println(args[i] + "=" + getProp(args[i]));
            }
        } else if (mode.equals("exec")) {
            StringBuilder cmd = new StringBuilder();
            for (int i = 2; i < args.length; i++) { if (i > 2) cmd.append(' '); cmd.append(args[i]); }
            System.out.println(execAsTarget(cmd.toString()));
        } else if (mode.equals("setprop2")) {
            setPropSuspend(args[2], args[3]);
        } else if (mode.equals("setprop")) {
            setProp(args[2], args[3]);
        } else if (mode.equals("getbool")) {
            System.out.println(readBool(args[2], args[3]));
        } else if (mode.equals("patchbool")) {
            System.out.println(patchBool(args[2], args[3], Boolean.parseBoolean(args[4])));
        } else if (mode.equals("dump")) {
            String kw = args.length > 2 ? args[2].toLowerCase() : "";
            dumpClasses(kw);
        } else {
            usage();
        }
    }

    private static void usage() {
        System.out.println("usage: info|props|exec|dump <port> [args...]");
    }

    // ------------------------------------------------------------ JDWP plumbing

    private static void attach(int port) throws Exception {
        AttachingConnector connector = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) { connector = c; break; }
        }
        if (connector == null) throw new IllegalStateException("no SocketAttach connector");
        Map<String, Connector.Argument> cargs = connector.defaultArguments();
        cargs.get("hostname").setValue("127.0.0.1");
        cargs.get("port").setValue(String.valueOf(port));
        vm = connector.attach(cargs);
    }

    /** 取一个被「MethodEntry 事件」挂起的线程（只有事件挂起的线程才能 invoke）。 */
    private static boolean suspendedThread() {
        MethodEntryRequest req = vm.eventRequestManager().createMethodEntryRequest();
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        req.enable();
        try {
            for (int rounds = 0; rounds < 40; rounds++) {
                EventSet events;
                try {
                    events = vm.eventQueue().remove(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (events == null) return false;
                boolean consumed = false;
                for (Event e : events) {
                    if (!(e instanceof MethodEntryEvent)) continue;
                    MethodEntryEvent me = (MethodEntryEvent) e;
                    ThreadReference t = me.thread();
                    if (t == null) continue;
                    String tn = t.name();
                    if (tn != null && (tn.contains("Signal Catcher") || tn.contains("JDWP"))) continue;
                    try {
                        String mn = me.method().name();
                        if (mn.equals("<clinit>") || mn.equals("<init>")) continue;
                        if (t.frameCount() <= 0) continue;
                        thread = t;
                        req.disable();
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
                events.resume();
            }
        } finally {
            try { req.disable(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static ReferenceType findClass(String name) {
        List<ReferenceType> l = vm.classesByName(name);
        if (l.isEmpty()) throw new IllegalStateException("class not loaded: " + name);
        return l.get(0);
    }

    private static Method findMethod(ReferenceType rt, String name, int argc) {
        Method m = findMethodOrNull(rt, name, argc);
        if (m == null) throw new IllegalStateException("method not found: " + rt.name() + "." + name + "/" + argc);
        return m;
    }

    private static Method findMethodOrNull(ReferenceType rt, String name, int argc) {
        ReferenceType cur = rt;
        while (cur != null) {
            for (Method m : cur.methodsByName(name)) {
                if (m.argumentTypeNames().size() == argc) return m;
            }
            if (cur instanceof ClassType) {
                cur = ((ClassType) cur).superclass();
            } else {
                break;
            }
        }
        // 接口兜底
        List<InterfaceType> ifaces = rt instanceof ClassType ? ((ClassType) rt).allInterfaces() : Collections.emptyList();
        for (InterfaceType it : ifaces) {
            for (Method m : it.methodsByName(name)) {
                if (m.argumentTypeNames().size() == argc) return m;
            }
        }
        return null;
    }

    private static Value invokeStatic(String cls, String method, List<Value> args) throws Exception {
        ReferenceType rt = findClass(cls);
        Method m = findMethod(rt, method, args.size());
        return ((ClassType) rt).invokeMethod(thread, m, args, 0);
    }

    private static Value invoke(ObjectReference obj, String method, List<Value> args) throws Exception {
        Method m = findMethod(obj.referenceType(), method, args.size());
        return obj.invokeMethod(thread, m, args, 0);
    }

    private static long intCall(String cls, String method) throws Exception {
        Value v = invokeStatic(cls, method, new ArrayList<>());
        return ((IntegerValue) v).longValue();
    }

    private static String strCall(String cls, String method) {
        try {
            Value v = invokeStatic(cls, method, new ArrayList<>());
            return v == null ? "null" : ((StringReference) v).value();
        } catch (Throwable t) { return "ERR:" + t.getMessage(); }
    }

    private static String getProp(String key) {
        try {
            List<Value> args = new ArrayList<>();
            args.add(vm.mirrorOf(key));
            Value v = invokeStatic("android.os.SystemProperties", "get", args);
            return v == null ? "null" : ((StringReference) v).value();
        } catch (Throwable t) { return "ERR:" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    // ------------------------------------------------------------ exec

    private static String execAsTarget(String command) throws Exception {
        String path = writeScript("#!" + "/system/bin/sh" + "\n" + command + "\n");
        if (path.startsWith("ERR:")) return path;
        ReferenceType rt = findClass("java.lang.Runtime");
        Method getRuntime = findMethod(rt, "getRuntime", 0);
        ObjectReference runtime = (ObjectReference) ((ClassType) rt).invokeMethod(thread, getRuntime, new ArrayList<>(), 0);
        Method exec = findMethod(rt, "exec", 1);
        List<Value> args = new ArrayList<>();
        args.add(vm.mirrorOf("/system/bin/sh " + path));
        ObjectReference proc = (ObjectReference) runtime.invokeMethod(thread, exec, args, 0);
        Method waitFor = findMethod(proc.referenceType(), "waitFor", 0);
        try {
            proc.invokeMethod(thread, waitFor, new ArrayList<>(), 0);
        } catch (Throwable ignored) {}
        return (readAll(proc, "getInputStream") + readAll(proc, "getErrorStream")).trim();
    }

    private static String writeScript(String content) {
        List<String> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            ReferenceType at = findClass("android.app.ActivityThread");
            Method currentApp = findMethodOrNull(at, "currentApplication", 0);
            if (currentApp != null) {
                ObjectReference app = (ObjectReference) ((ClassType) at).invokeMethod(thread, currentApp, new ArrayList<>(), 0);
                if (app != null) {
                    ObjectReference filesDir = (ObjectReference) invoke(app, "getFilesDir", new ArrayList<>());
                    if (filesDir != null) {
                        String path = ((StringReference) invoke(filesDir, "getAbsolutePath", new ArrayList<>())).value();
                        if (path != null) candidates.add(path + "/.jdwp_cmd.sh");
                    }
                }
            }
        } catch (Throwable t) {
            errors.add("filesDir:" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        candidates.add("/sdcard/Download/.jdwp_cmd.sh");
        candidates.add("/sdcard/.jdwp_cmd.sh");
        candidates.add("/data/local/tmp/.jdwp_cmd.sh");
        ReferenceType fosClass;
        try {
            fosClass = findClass("java.io.FileOutputStream");
        } catch (Throwable t) {
            return "ERR: FileOutputStream class not loaded: " + t;
        }
        Method ctor = null;
        for (Method m : fosClass.methodsByName("<init>")) {
            if (m.argumentTypeNames().equals(Collections.singletonList("java.lang.String"))) { ctor = m; break; }
        }
        if (ctor == null) return "ERR: FileOutputStream(String) not found";
        for (String p : candidates) {
            try {
                List<Value> a = new ArrayList<>();
                a.add(vm.mirrorOf(p));
                ObjectReference fos = ((ClassType) fosClass).newInstance(thread, ctor, a, 0);
                Method write = findMethod(fosClass, "write", 1);
                for (byte b : content.getBytes(StandardCharsets.UTF_8)) {
                    List<Value> wa = new ArrayList<>();
                    wa.add(vm.mirrorOf(b & 0xff));
                    fos.invokeMethod(thread, write, wa, 0);
                }
                invoke(fos, "close", new ArrayList<>());
                return p;
            } catch (Throwable t) {
                errors.add(p + ":" + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ":" + t.getMessage()));
            }
        }
        return "ERR: cannot write script (" + String.join(" | ", errors) + ")";
    }

    private static String readAll(ObjectReference proc, String getter) throws Exception {
        ObjectReference stream = (ObjectReference) invoke(proc, getter, new ArrayList<>());
        if (stream == null) return "";
        Method read = findMethod(stream.referenceType(), "read", 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65536; i++) {
            Value v = stream.invokeMethod(thread, read, new ArrayList<>(), 0);
            int b = ((IntegerValue) v).value();
            if (b < 0) break;
            sb.append((char) b);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ setprop

    private static Method findMethod(ReferenceType rt, String name, String sig) {
        for (Method m : rt.methods()) {
            if (m.name().equals(name) && m.signature().equals(sig)) return m;
        }
        return null;
    }

    private static void setPropSuspend(String key, String value) throws Exception {
        vm.suspend();
        try {
            ReferenceType rt = findClass("android.os.SystemProperties");
            Method m = findMethod(rt, "set", "(Ljava/lang/String;Ljava/lang/String;)V");
            if (m == null) throw new IllegalStateException("SystemProperties.set not found");
            ThreadReference t = vm.allThreads().get(0);
            java.util.List<Value> vals = new java.util.ArrayList<>();
            vals.add(vm.mirrorOf(key));
            vals.add(vm.mirrorOf(value));
            ((ClassType) rt).invokeMethod(t, m, vals, ObjectReference.INVOKE_SINGLE_THREADED);
            System.out.println("setprop2 " + key + "=" + value);
        } finally {
            vm.resume();
        }
    }

    private static void setProp(String key, String value) throws Exception {
        ReferenceType rt = findClass("android.os.SystemProperties");
        Method m = findMethod(rt, "set", "(Ljava/lang/String;Ljava/lang/String;)V");
        if (m == null) throw new IllegalStateException("SystemProperties.set not found");
        java.util.List<Value> vals = new java.util.ArrayList<>();
        vals.add(vm.mirrorOf(key));
        vals.add(vm.mirrorOf(value));
        ((ClassType) rt).invokeMethod(thread, m, vals, ObjectReference.INVOKE_SINGLE_THREADED);
        System.out.println("setprop " + key + "=" + value);
    }

    // ------------------------------------------------------------ patch

    private static Field findField(ReferenceType rt, String name) {
        ReferenceType cur = rt;
        while (cur != null) {
            Field f = cur.fieldByName(name);
            if (f != null) return f;
            cur = (cur instanceof ClassType) ? ((ClassType) cur).superclass() : null;
        }
        return null;
    }

    private static ObjectReference firstInstance(ClassType ct) throws Exception {
        List<ObjectReference> inst = ct.instances(10);
        if (inst == null || inst.isEmpty()) return null;
        return inst.get(0);
    }

    private static String readBool(String cls, String fieldName) throws Exception {
        vm.suspend();
        try {
            ReferenceType rt = findClass(cls);
            Field f = findField(rt, fieldName);
            if (f == null) return "ERR field not found: " + fieldName;
            Value v;
            if (f.isStatic()) v = ((ClassType) rt).getValue(f);
            else {
                ObjectReference inst = firstInstance((ClassType) rt);
                if (inst == null) return "ERR no instance";
                v = inst.getValue(f);
            }
            return fieldName + "=" + v;
        } finally {
            vm.resume();
        }
    }

    private static String patchBool(String cls, String fieldName, boolean value) throws Exception {
        vm.suspend();
        try {
            ReferenceType rt = findClass(cls);
            Field f = findField(rt, fieldName);
            if (f == null) return "ERR field not found: " + fieldName;
            if (f.isStatic()) {
                ((ClassType) rt).setValue(f, vm.mirrorOf(value));
                return "patched static " + cls + "." + fieldName + "=" + value;
            }
            ObjectReference inst = firstInstance((ClassType) rt);
            if (inst == null) return "ERR no instance of " + cls;
            inst.setValue(f, vm.mirrorOf(value));
            Value cur = inst.getValue(f);
            return "patched " + cls + "." + fieldName + "=" + cur;
        } finally {
            vm.resume();
        }
    }

    // ------------------------------------------------------------ dump

    private static void dumpClasses(String keyword) {
        int classCount = 0, match = 0;
        for (ReferenceType rt : vm.allClasses()) {
            classCount++;
            String cn = rt.name();
            if (!keyword.isEmpty() && !cn.toLowerCase().contains(keyword)) continue;
            List<Method> hits = new ArrayList<>();
            for (Method m : rt.methods()) {
                if (keyword.isEmpty()
                        || m.name().toLowerCase().contains(keyword)
                        || m.argumentTypeNames().toString().toLowerCase().contains(keyword)) {
                    hits.add(m);
                }
            }
            if (keyword.isEmpty() || !hits.isEmpty()) {
                match++;
                System.out.println("CLASS " + cn);
                for (Method m : hits) System.out.println("   " + m.name() + m.signature());
            }
        }
        System.out.println("--- classes=" + classCount + " matched=" + match);
    }
}
