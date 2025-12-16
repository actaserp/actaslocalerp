package mes.app.notification;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.Notification;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.NotificationRepository;
import mes.sse.Service.SseService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    private final SseService sseService;

    @Transactional
    @PostMapping("/read")
    public AjaxResult markAsRead(@RequestParam Integer notiId) {
        AjaxResult result = new AjaxResult();
        notificationRepository.markAsRead(notiId);
        return result;
    }

    @Transactional
    @PostMapping("/readAll")
    public AjaxResult markAllAsRead(@RequestParam String spjangcd, Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String userId = user.getUsername();

        notificationRepository.markAllAsRead(spjangcd, userId);

        return result;
    }

    @Transactional
    @PostMapping("/reply")
    public AjaxResult reply(@RequestParam Integer parentNotiId,
                            @RequestParam String message,
                            @RequestParam String spjangcd,
                            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        String senderUserId = user.getUsername();
        String senderUserName = user.getFirst_name();

        // 1️⃣ 원 알림 조회
        Notification parent = notificationRepository.findById(parentNotiId)
                .orElseThrow(() -> new RuntimeException("알림 없음"));

        // 읽음 처리
        notificationRepository.markAsRead(parentNotiId);
        
        // 2️⃣ 답장 알림 생성
        Notification reply = new Notification();
        reply.setDomain(parent.getDomain());
        reply.setAction("REPLY");
        reply.setTargetId(parent.getTargetId());
        reply.setTitle("답장");
        reply.setMessage(message);

        reply.setSenderUserId(senderUserId);
        reply.setSenderUserName(senderUserName);
        reply.setReceiverUserId(parent.getSenderUserId());
        reply.setParentNotiId(parentNotiId);
        reply.setSpjangcd(spjangcd);

        notificationRepository.save(reply);

        // 3️⃣ SSE 즉시 전송
        sseService.sendComment(reply);

        return result;
    }


}