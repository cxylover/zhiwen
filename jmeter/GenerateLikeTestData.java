import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class GenerateLikeTestData {
    private static final long[] POST_IDS = {
            305978636022976512L,
            306010618199150592L,
            306012407921250304L,
            306018405683695616L,
            306023544435904512L,
            312045628760920064L,
            312099950437732352L,
            305696708409561088L,
            305937767383306240L
    };

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        PrivateKey privateKey = loadPrivateKey(options.privateKeyPath);

        long now = Instant.now().getEpochSecond();
        long exp = now + options.ttlHours * 3600L;
        String header = "{\"kid\":\"" + options.keyId + "\",\"alg\":\"RS256\"}";
        String encodedHeader = base64Url(header.getBytes(StandardCharsets.UTF_8));

        List<String> lines = new ArrayList<>(options.count + 1);
        lines.add("entityId,jwtToken");

        for (int i = 0; i < options.count; i++) {
            long userId = options.startUserId + i;
            long entityId = POST_IDS[i % POST_IDS.length];
            String payload = "{"
                    + "\"sub\":\"" + userId + "\","
                    + "\"uid\":" + userId + ","
                    + "\"iss\":\"" + options.issuer + "\","
                    + "\"nickname\":\"load-user-" + userId + "\","
                    + "\"exp\":" + exp + ","
                    + "\"token_type\":\"access\","
                    + "\"iat\":" + now + ","
                    + "\"jti\":\"" + UUID.randomUUID() + "\""
                    + "}";

            String unsignedToken = encodedHeader + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
            String jwt = unsignedToken + "." + sign(unsignedToken, privateKey);
            lines.add(entityId + "," + jwt);
        }

        Path output = options.outputPath.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, lines, StandardCharsets.UTF_8);

        System.out.println("Generated rows: " + options.count);
        System.out.println("Output: " + output);
        System.out.println("Start user id: " + options.startUserId);
        System.out.println("TTL hours: " + options.ttlHours);
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String sign(String unsignedToken, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        return base64Url(signature.sign());
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Options(
            int count,
            long startUserId,
            Path outputPath,
            Path privateKeyPath,
            String issuer,
            String keyId,
            int ttlHours
    ) {
        static Options parse(String[] args) throws IOException {
            int count = 20_000;
            long startUserId = 1_000_000L;
            Path outputPath = Path.of("jmeter", "test-data-20000.csv");
            Path privateKeyPath = Path.of("src", "main", "resources", "keys", "private.pem");
            String issuer = "zhiguang";
            String keyId = "zhiguang-key";
            int ttlHours = 24;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = null;
                int equalsIndex = arg.indexOf('=');
                if (equalsIndex > 0) {
                    value = arg.substring(equalsIndex + 1);
                    arg = arg.substring(0, equalsIndex);
                } else if (i + 1 < args.length) {
                    value = args[++i];
                }

                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }

                switch (arg) {
                    case "--count" -> count = Integer.parseInt(value);
                    case "--start-user-id" -> startUserId = Long.parseLong(value);
                    case "--output" -> outputPath = Path.of(value);
                    case "--private-key" -> privateKeyPath = Path.of(value);
                    case "--issuer" -> issuer = value;
                    case "--kid" -> keyId = value;
                    case "--ttl-hours" -> ttlHours = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            if (count <= 0) {
                throw new IllegalArgumentException("--count must be greater than 0");
            }
            if (!Files.exists(privateKeyPath)) {
                throw new IOException("Private key not found: " + privateKeyPath.toAbsolutePath().normalize());
            }
            return new Options(count, startUserId, outputPath, privateKeyPath, issuer, keyId, ttlHours);
        }
    }
}
