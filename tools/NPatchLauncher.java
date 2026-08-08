import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;

/**
 * NPatch 启动器。
 *
 * 存在的原因：NPatch 的 jar 在打包时无条件调用 KeyStore.getInstance("BKS")。
 * BKS 是 BouncyCastle 的专有 keystore 格式，Android 运行时自带，但标准桌面 JDK
 * 不提供 —— 于是在 PC 上直接 java -jar 就会 "BKS KeyStore not available"。
 *
 * BouncyCastleProvider 本身已经打包在 NPatch 的 jar 内（约 4900 个 class），
 * 只是从未被注册为 security provider。这里在移交控制权之前把它注册上。
 *
 * 试过但不行的方案：-Djava.security.properties=... 追加 provider。
 * 那个机制在 JVM 早期用 bootstrap classloader 解析 provider 类名，
 * 而 BouncyCastle 位于 application classpath 上，那时还看不见。
 *
 * 用法（把原本给 NPatch 的参数原样跟在后面）：
 *   javac -cp <npatch.jar> -d <outdir> NPatchLauncher.java
 *   java -cp "<npatch.jar>;<outdir>" NPatchLauncher <原有参数...>
 */
public class NPatchLauncher {

    private static final String BC_PROVIDER =
            "org.bouncycastle.jce.provider.BouncyCastleProvider";

    private static final String NPATCH_MAIN = "top.nkbe.npatch.patch.NPatch";

    public static void main(String[] args) throws Exception {
        registerBouncyCastle();

        // 反射调用，避免对 NPatch 的类形成编译期依赖 —— 这样即使日后
        // NPatch 换版本，只要主类名不变，本启动器就无需重新编译。
        Method main = Class.forName(NPATCH_MAIN).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    private static void registerBouncyCastle() throws Exception {
        if (Security.getProvider("BC") != null) {
            System.out.println("[launcher] BouncyCastle 已注册，跳过");
            return;
        }
        Provider provider = (Provider) Class.forName(BC_PROVIDER)
                .getDeclaredConstructor()
                .newInstance();
        Security.addProvider(provider);
        System.out.println("[launcher] 已注册 BouncyCastle provider，BKS keystore 可用");
    }
}
