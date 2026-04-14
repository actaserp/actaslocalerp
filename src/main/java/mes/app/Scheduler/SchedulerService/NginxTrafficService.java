package mes.app.Scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.traffic.TrafficService;
import mes.app.traffic.util.NginxLogParser;

import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class NginxTrafficService {

    private final TrafficService trafficService;

    private static final String LOG_ROOT = "/var/log/nginx";

    public void collectYesterdayTraffic() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateSuffix = yesterday.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        log.info("[트래픽 집계] 시작 - 대상날짜: {}", yesterday);

        File root = new File(LOG_ROOT);
        File[] serviceDirs = root.listFiles(File::isDirectory);

        if (serviceDirs == null || serviceDirs.length == 0) {
            log.warn("[트래픽 집계] 하위 폴더 없음: {}", LOG_ROOT);
            return;
        }

        // 디버깅: 발견된 폴더 목록
        log.info("[트래픽 집계] 발견된 폴더 수: {}, 목록: {}",
                serviceDirs.length,
                Arrays.stream(serviceDirs).map(File::getName).toList());

        int successCount = 0;
        int failCount = 0;

        for (File serviceDir : serviceDirs) {
            String service = serviceDir.getName();

            // 디버깅: 각 폴더 처리 시작
            log.info("[트래픽 집계] 폴더 처리 시작: {}", service);

            try {
                File logFile = resolveLogFile(serviceDir, dateSuffix);

                if (logFile == null) {
                    log.warn("[트래픽 집계] 파일 없음 - 서비스: {}, 패턴: access.log-{}", service, dateSuffix);
                    failCount++;
                    continue;
                }

                log.info("[트래픽 집계] 파싱 시작 - 서비스: {}, 파일: {}", service, logFile.getName());

                NginxLogParser.TrafficResult result = parseLogFile(logFile);

                trafficService.saveTraffic(service, result, yesterday);

                log.info("[트래픽 집계] 완료 - 서비스: {}, 요청수: {}, 트래픽: {} bytes",
                        service, result.requestCount, result.totalBytes);
                successCount++;

            } catch (Exception e) {
                log.error("[트래픽 집계] 처리 실패 - 서비스: {}, 오류: {}",
                        service, e.getMessage(), e);
                failCount++;
            }
        }

        log.info("[트래픽 집계] 종료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    private File resolveLogFile(File serviceDir, String dateSuffix) {
        File gzFile = new File(serviceDir, "access.log-" + dateSuffix + ".gz");
        if (gzFile.exists()) return gzFile;

        File plainFile = new File(serviceDir, "access.log-" + dateSuffix);
        if (plainFile.exists()) return plainFile;

        return null;
    }

    private NginxLogParser.TrafficResult parseLogFile(File logFile) throws IOException {
        try (InputStream fis = new FileInputStream(logFile);
             InputStream is = logFile.getName().endsWith(".gz")
                     ? new GZIPInputStream(fis)
                     : fis) {
            return NginxLogParser.parseStream(is);
        }
    }
}