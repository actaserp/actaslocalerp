package mes.app.Scheduler;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.SchedulerService.AccountSyncService;
import mes.app.Scheduler.SchedulerService.ApiUsageService;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;

@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledTaskRunner {

    private final Executor schedulerExecutor;

    private final AccountSyncService accountSyncService;
    private final ApiUsageService apiUsageService;

    //@Scheduled(cron = "0 5 * * * *")
    @Scheduled(cron = "0 0 * * * *") //5분주기
    public void runScheduledTasks() {
        //TODO: 작업 추가할거면 SchedulerThreadPoolConfig 에서 쓰레드풀 조정해주삼 지금 작업하나밖에 없어서 2개로 해놨삼

        //log.info("[스케줄러 시작] 계좌수집 작업 시작 - Thread: {}", Thread.currentThread().getName());

        schedulerExecutor.execute(() -> safeRun(accountSyncService::run, "계좌수집"));
        //schedulerExecutor.execute(() -> safeRun(apiTimeLogCollectService::run, "API경과시간"));
    }

    /**
     * [SaaS 인증용] 매일 새벽 0시 5분에 전날 Redis API 호출 내역을 DB로 이관
     * TODO: migrateDailyApiUsage에서 시간수정, 여기서 스케줄러 시간수정 필
     */
    //@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    @Scheduled(cron = "0 35 14 17 * *", zone = "Asia/Seoul")
    public void runApiUsageMigration(){

        // 스케줄러가 인식하는 현재 시간 로그 출력
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[스케줄러 감지] 현재 서버 시간: {} | 작업명: api 콜 집계", currentTime);

        schedulerExecutor.execute(() -> safeRun(apiUsageService::migrateMonthlyApiUsage, "api 콜 집계"));
    }


    private void safeRun(Runnable task, String taskName){
        try{
            task.run();
        }catch (Exception e){
            log.error("스케줄러 작업 실패: {} - {}", taskName, e.getMessage(), e);
        }
    }
}
