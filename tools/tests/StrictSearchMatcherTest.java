import com.github.xingzheli.bilidetox.hook.StrictSearchMatcher;

/** Run against the compiled debug Kotlin classes; no Android runtime required. */
public class StrictSearchMatcherTest {
    private static void check(boolean expected, String query, String title, String desc, String author) {
        boolean actual = StrictSearchMatcher.INSTANCE.matches(query, title, desc, author);
        if (actual != expected) throw new AssertionError(query + " / " + title + " / " + author);
    }
    public static void main(String[] args) throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        var completions = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread caller = Thread.currentThread();
        for (int i = 0; i < 10; i++) {
            StrictSearchMatcher.INSTANCE.preload(elapsed -> {
                if (Thread.currentThread() == caller) failure.set(new AssertionError("loaded on caller thread"));
                completions.incrementAndGet();
                ready.countDown();
                return kotlin.Unit.INSTANCE;
            }, error -> {
                failure.set(error);
                ready.countDown();
                return kotlin.Unit.INSTANCE;
            });
        }
        if (!ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("preload timed out");
        if (failure.get() != null) throw new AssertionError("preload failed", failure.get());
        if (completions.get() != 1) throw new AssertionError("duplicate preload");
        check(false, "3B1B", "【官方双语】毛球定理", "", "3Blue1Brown");
        check(true, "３ｂ１ｂ", "<em>3B1B</em> 数学", "", "转载者");
        check(false, "3B1B", "今日份释放压抑", "", "我拉磨贼快");
        check(false, "3B1B", "肉呼呼的大腿陪你3小时", "", "B1ue゛");
        check(false, "3B1B", "普罗米修斯", "", "电影解说");
        check(true, "Python 入门教程", "PYTHON 课程", "适合初学者的入门教程", "讲师");
        check(true, "Python 入门教程", "Python 进阶", "", "讲师");
        check(true, "Python 入门", "Py", "thon 入门", "讲师");
        check(false, "Python", "Py", "thon", "讲师");
        check(true, "线性代数", "矩阵", "线性代数的应用", "讲师");
        check(true, "线性代数入门", "代数课程", "", "讲师");
        check(false, "线性代数入门", "今日份释放压抑", "今天的推荐", "讲师");
        check(false, "怎么学习线性代数", "怎么做饭", "", "厨师");
        check(true, "猫", "猫咪", "", "作者");
        check(true, "C++", "Ｃ＋＋教程", "", "讲师");
        check(false, "C++", "C语言教程", "", "讲师");
        check(false, "C#", "C语言教程", "", "讲师");
        check(true, "", "视频", "", "作者");
        if (!StrictSearchMatcher.INSTANCE.keywords("3B1B").equals(java.util.List.of("3b1b")))
            throw new AssertionError("ASCII query split");
        if (!StrictSearchMatcher.INSTANCE.keywords("C++").equals(java.util.List.of("c++")))
            throw new AssertionError("language symbols lost");
        System.out.println("StrictSearchMatcher: 20 matching checks + background single preload passed; tokens=" +
            StrictSearchMatcher.INSTANCE.keywords("怎么学习线性代数入门"));
    }
}
