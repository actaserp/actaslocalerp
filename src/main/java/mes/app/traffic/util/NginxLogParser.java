package mes.app.traffic.util;

import lombok.AllArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NginxLogParser {

    private NginxLogParser() {}

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "\\S+ - - \\[[^]]+] \"\\S+ (\\S+) \\S+\" (\\d+) (\\d+)"
    );

    public static TrafficResult parseFile(File file) {
        long totalBytes = 0L;
        long requestCount = 0L;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Long bytes = parseLine(line);
                if (bytes != null) { totalBytes += bytes; requestCount++; }
            }
        } catch (IOException e) {
            throw new RuntimeException("로그 파일 읽기 실패: " + file.getPath(), e);
        }
        return new TrafficResult(totalBytes, requestCount);
    }

    public static TrafficResult parseStream(MultipartFile file) {
        long totalBytes = 0L;
        long requestCount = 0L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Long bytes = parseLine(line);
                if (bytes != null) { totalBytes += bytes; requestCount++; }
            }
        } catch (IOException e) {
            throw new RuntimeException("로그 스트림 읽기 실패: " + file.getOriginalFilename(), e);
        }
        return new TrafficResult(totalBytes, requestCount);
    }

    private static Long parseLine(String line) {
        Matcher matcher = LOG_PATTERN.matcher(line);
        if (!matcher.find()) return null;

        String path   = matcher.group(1);
        int    status = Integer.parseInt(matcher.group(2));
        long   bytes  = Long.parseLong(matcher.group(3));

        if (path.contains("/health"))      return null;
        if (status < 200 || status >= 300) return null;

        return bytes;
    }

    @AllArgsConstructor
    public static class TrafficResult {
        public final long totalBytes;
        public final long requestCount;
    }
}
