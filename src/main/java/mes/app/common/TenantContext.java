package mes.app.common;

public class TenantContext {
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    // 사업장 코드 저장
    public static void set(String tenantId) {
        currentTenant.set(tenantId);
    }

    // 사업장 코드 조회
    public static String get() {
        return currentTenant.get();
    }

    // 컨텍스트 초기화 (메모리 누수 방지 필수)
    public static void clear() {
        currentTenant.remove();
    }
}