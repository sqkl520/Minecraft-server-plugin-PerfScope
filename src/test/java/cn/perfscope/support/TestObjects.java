/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.support;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 测试作用域的唯一反射入口：{@code Unsafe.allocateInstance} + 实例字段读写。
 *
 * <p>集中在这里的理由是<b>约束</b>而不是省事。同一套三件套此前被复制进 11 个测试类，
 * 每份都附带各自的踩坑注释；写在一起之后，下面三条只需写一次。</p>
 *
 * <h2>一、禁用 {@code Unsafe.objectFieldOffset} / {@code putObject}</h2>
 * <p>两者自 JDK 24 起是 terminal deprecation：带 {@code --sun-misc-unsafe-memory-access=deny}
 * 启动会直接抛 {@code UnsupportedOperationException}。它们的典型用途是「写 {@code final}
 * 实例字段」，而那件事 {@link Field#set} 完全能做，且在 JDK 17/21/24 上行为一致。
 * 所以本类写字段一律走 {@code Field.set}。{@code allocateInstance} 是 {@code Unsafe}
 * 里唯一仍在使用的成员——它负责"造对象"，不负责"改字段"，不受上述弃用影响。</p>
 *
 * <h2>二、{@code allocateInstance} 只给"构造器真的要 Bukkit"的类用</h2>
 * <p>它<b>不执行字段初始化器</b>，于是每个 {@code final} 容器字段都留在 {@code null}，
 * 调用方必须逐个补；补漏了就是"测试看起来跑过了"的 NPE。能用普通构造器就别用它。</p>
 * <p>本仓库实测分界：</p>
 * <ul>
 *   <li><b>必须</b>：{@link cn.perfscope.PerfScope}（Bukkit 插件类）、
 *       {@link cn.perfscope.alert.AlertService}（构造器调 {@code ServerCompat.isFolia()}）、
 *       {@link cn.perfscope.alert.WebhookNotifier}、{@link cn.perfscope.diagnose.DiagnosticEngine}
 *       （解引用 {@code plugin.getConfigManager()}）；
 *       还有 {@link cn.perfscope.metrics.MetricsCollector}（同上，且会触发 {@code ServerCompat} 静态初始化）。</li>
 *   <li><b>不需要</b>：{@link cn.perfscope.history.HistoryStore}、
 *       {@link cn.perfscope.config.ConfigManager}、{@link cn.perfscope.history.HistoryFileStore}
 *       ——构造器只赋字段。{@code new ConfigManager(null)}、
 *       {@code new HistoryStore(cm, logger)}、{@code new HistoryFileStore(cm, path, logger)}
 *       在无 Bukkit 的 JVM 里都能构造成功。</li>
 * </ul>
 *
 * <h2>三、反射夹具依赖「classpath 构建」，即没有 {@code module-info.java}</h2>
 * <p>本仓库按 classpath 打包，所有类型都在<b>未命名模块</b>里，{@code setAccessible(true)}
 * 因此总是成功。一旦加上 {@code module-info.java}，未导出包里的 {@code setAccessible} 会抛
 * {@code InaccessibleObjectException}——几百个测试同时变红。{@link #assertReflectionUsable()}
 * 把这件事变成一条第一时间炸掉、且消息可读的自检。</p>
 */
public final class TestObjects {

    private static final Object UNSAFE;
    private static final Method ALLOCATE_INSTANCE;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            ALLOCATE_INSTANCE = unsafeClass.getMethod("allocateInstance", Class.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestObjects() {
    }

    /**
     * 不经构造器与字段初始化器分配实例。只用于构造器确实依赖 Bukkit 的类（见类注释二）。
     * 分配出来的对象所有 {@code final} 容器字段都是 {@code null}，调用方负责补齐。
     */
    public static <T> T allocate(Class<T> type) {
        try {
            return type.cast(ALLOCATE_INSTANCE.invoke(UNSAFE, type));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("allocateInstance failed for " + type.getName(), e);
        }
    }

    /**
     * 沿父类链写实例字段（含 {@code final} 实例字段——{@code Field.set} 对它是允许的）。
     *
     * <p>找不到时抛 {@link IllegalStateException} 而非静默成功：字段被改名时应当场变红，
     * 而不是让一个"什么都没写进去"的测试继续绿。{@code value} 为 {@code null} 合法。</p>
     */
    public static void setField(Class<?> declaringType, Object target, String name, Object value) {
        try {
            findField(declaringType, name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot write " + declaringType.getName() + "#" + name, e);
        }
    }

    /** 沿父类链读实例字段。用途：断言被测代码真的改了这个字段。 */
    public static Object getField(Class<?> declaringType, Object target, String name) {
        try {
            return findField(declaringType, name).get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + declaringType.getName() + "#" + name, e);
        }
    }

    /** {@link #getField} 的 {@code int} 便捷形式（读计数器时免强转）。 */
    public static int getInt(Class<?> declaringType, Object target, String name) {
        return (Integer) getField(declaringType, target, name);
    }

    /** 读 {@code static} 字段／常量。与 {@link #getField} 分开只为调用处可读（不必写那个 null）。 */
    public static int getStaticInt(Class<?> declaringType, String name) {
        return (Integer) getField(declaringType, null, name);
    }

    /**
     * 调用私有方法。实例方法传对象本身；{@code static} 方法传<b>声明类</b>（不是 {@code null}）：
     * {@code TestObjects.invoke(DiagnosticEngine.class, "reloadGraceMs", ...)}。
     *
     * <p>目标方法抛出的异常解包后原样重抛：{@code InvocationTargetException} 是反射的记账凭证，
     * 不是被测代码的行为，留着它会让断言失败信息变成一句无用的 "null"。</p>
     */
    public static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException(
                "invoke target is null: for a static method pass the declaring Class, "
                    + "e.g. TestObjects.invoke(Foo.class, \"" + methodName + "\", ...)");
        }
        boolean isStatic = target instanceof Class<?>;
        Class<?> owner = isStatic ? (Class<?>) target : target.getClass();
        try {
            Method m = findMethod(owner, methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(isStatic ? null : target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("invoke " + methodName + " failed", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("invoke " + methodName + " failed", e);
        }
    }

    /**
     * 自检：反射在当前 JVM 的模块形态下确实可用（见类注释三）。
     * 测试类可在 {@code @BeforeAll} 里调一次（可选，不是强制）。
     *
     * @throws AssertionError 当类型被放进了具名模块
     */
    public static void assertReflectionUsable() {
        if (TestObjects.class.getModule().isNamed()) {
            throw new AssertionError(
                "TestObjects 依赖 classpath 构建（未命名模块）：检测到具名模块 "
                    + TestObjects.class.getModule().getName()
                    + "。加入 module-info.java 后所有 setAccessible 都会抛 "
                    + "InaccessibleObjectException，反射夹具需要整体改造。");
        }
    }

    // ==================== 内部 ====================

    /** 从 {@code declaringType} 起逐级上溯查找并放开字段。 */
    private static Field findField(Class<?> declaringType, String name) {
        for (Class<?> c = declaringType; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 继续上溯
            }
        }
        throw new IllegalStateException(
            "no field '" + name + "' in " + declaringType.getName() + " or its superclasses"
                + " (renamed in production code?)");
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] paramTypes)
        throws NoSuchMethodException {
        Class<?> c = owner;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name + " in " + owner.getName());
    }
}
