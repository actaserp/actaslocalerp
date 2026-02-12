package mes.app.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 저장
    public void setValues(String key, Object value){
        redisTemplate.opsForValue().set(key, value);
    }

    // 저장, 만료시간 설정
    public void setValues(String key, Object value, long duration, TimeUnit unit){
        redisTemplate.opsForValue().set(key,value, duration,unit);
    }

    // 3. 데이터 조회 (GET)
    public Object getValues(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    // 4. [핵심] API 카운트용 숫자 증가 (INCR)
    public Long incrementValue(String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    // 5. 데이터 삭제 (DEL)
    public void deleteValues(String key) {
        redisTemplate.delete(key);
    }
}
