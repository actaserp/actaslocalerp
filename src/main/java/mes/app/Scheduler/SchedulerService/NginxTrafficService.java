package mes.app.Scheduler.SchedulerService;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.traffic.TrafficService;
import mes.app.traffic.dto.TrafficUploadResponse;
import mes.app.traffic.util.NginxLogParser;
import mes.domain.entity.TrafficDaily;
import mes.domain.repository.TrafficDailyRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mes.app.traffic.util.TrafficUtil.toGb;
import static mes.app.traffic.util.TrafficUtil.toMb;

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

        for (File serviceDir : serviceDirs) {
            String service = serviceDir.getName();
            File logFile = new File(serviceDir, "access.log-" + dateSuffix);

            if (!logFile.exists()) {
                log.warn("[트래픽 집계] 파일 없음: {}", logFile.getPath());
                continue;
            }

            NginxLogParser.TrafficResult result = NginxLogParser.parseFile(logFile);
            trafficService.saveTraffic(service, result, yesterday);

            log.info("[트래픽 집계] {} → 요청: {}건 / {}MB ({}GB)",
                    service, result.requestCount, toMb(result.totalBytes), toGb(result.totalBytes));
        }
        log.info("[트래픽 집계] 완료");
    }
}
