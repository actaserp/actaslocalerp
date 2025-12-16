package mes.app.notification;


import lombok.RequiredArgsConstructor;
import mes.domain.entity.Notification;
import mes.domain.entity.User;
import mes.domain.repository.NotificationRepository;
import mes.domain.repository.UserRepository;
import mes.sse.Service.SseService;
import mes.sse.SseController;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SseService sseService;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Notification base, String receiverUserId) {

        // 🔥 보낸 사람 = 받은 사람이면 알림 생성 자체를 안 함
        if (base.getSenderUserId().equals(receiverUserId)) {
            return;
        }

        Notification noti = new Notification();
        noti.setDomain(base.getDomain());
        noti.setAction(base.getAction());
        noti.setTargetId(base.getTargetId());
        noti.setTitle(base.getTitle());
        noti.setMessage(base.getMessage());
        noti.setSenderUserId(base.getSenderUserId());
        noti.setReceiverUserId(receiverUserId);
        noti.setSpjangcd(base.getSpjangcd());
        noti.setReadYn("N");

        // 1️⃣ DB 저장
        Notification saved = notificationRepository.save(noti);
        notificationRepository.flush();

        // 2️⃣ SSE 전송
        sseService.sendNotification(saved);
    }


    @Transactional(readOnly = true)
    public List<Notification> getUnread(String userId, String spjangcd) {

        List<Notification> list =
                notificationRepository
                        .findByReceiverUserIdAndReadYnAndSpjangcdOrderByCreatedAtDesc(
                                userId, "N", spjangcd
                        );

        for (Notification n : list) {
            userRepository.findByUsername(n.getSenderUserId())
                    .ifPresent(u -> n.setSenderUserName(u.getFirst_name()));
        }

        return list;
    }
}
