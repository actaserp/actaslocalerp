package mes.app.Scheduler;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.SchedulerService.AccountSyncService;
import mes.app.SpringBatch.ApiTimeLogProcessor.job.ApiLogCollectBatchJobConfig;
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

import java.util.concurrent.Executor;

@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledTaskRunner {

    private final Executor schedulerExecutor;
    private final AccountSyncService accountSyncService;
    //private final ApiTimeLogCollectService apiTimeLogCollectService;
    private final JobLauncher jobLauncher;
    private final ApiLogCollectBatchJobConfig apiLogCollectBatchJobConfig;

    @Qualifier("ApiTimeLogProcesserJob")
    private final Job apiTimeLogProcesserJob;

    //@Scheduled(cron = "0 5 * * * *")
    @Scheduled(cron = "0 0 * * * *") //5분주기
    public void runScheduledTasks() {
        //TODO: 작업 추가할거면 SchedulerThreadPoolConfig 에서 쓰레드풀 조정해주삼 지금 작업하나밖에 없어서 2개로 해놨삼

        //log.info("[스케줄러 시작] 계좌수집 작업 시작 - Thread: {}", Thread.currentThread().getName());

        schedulerExecutor.execute(() -> safeRun(accountSyncService::run, "계좌수집"));
        //schedulerExecutor.execute(() -> safeRun(apiTimeLogCollectService::run, "API경과시간"));
    }

    //@Scheduled(cron = "0 0 3 * * *")
//    @Scheduled(cron = "0 29 * * * *")
    public void runLogProcessorBatchTasks(){

        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters();

            schedulerExecutor.execute(() -> safeRun(
                    () -> {
                        try{
                            jobLauncher.run(apiTimeLogProcesserJob, jobParameters);

                        }catch(Exception e){
                            throw new RuntimeException("Batch job execution failed", e);
                        }
                    }, "API경과시간"
            ));
    }


    private void safeRun(Runnable task, String taskName){
        try{
            task.run();
        }catch (Exception e){
            log.error("스케줄러 작업 실패: {} - {}", taskName, e.getMessage(), e);
        }
    }
}
