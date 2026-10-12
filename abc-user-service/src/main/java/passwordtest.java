import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class passwordtest {
    public static void main(String[] args) {
        // 和项目默认配置完全一致：版本$2a$，强度10
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        // 把"admin123"换成你想要的明文密码
        String rawPassword = "admin";
        String encodedPassword = encoder.encode(rawPassword);

        System.out.println("明文密码：" + rawPassword);
        System.out.println("加密后哈希：" + encodedPassword);
    }
}