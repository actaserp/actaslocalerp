package mes.app.clock.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 연차관리 — PB w_pb209 / wf_createyear() 이식 (elv 구현 기준).
 *
 * <h3>산정기준은 회계연도 고정이다</h3>
 * 매년 1월 1일에 일괄 발생하고, 발생분이 유효한 기간은 정산년도 1/1 ~ 12/31 이다.
 * elv 는 사업체마다 회계연도/입사일 기준을 고를 수 있게 돼 있지만 이 프로젝트는
 * 회계연도만 쓰므로 기준 분기와 그 설정(tb_tenant_db.annual_basis)을 들여오지 않았다.
 * 그래서 기간이 늘 달력연도와 같아 TB_PB209 에 startdate/enddate 컬럼이 필요 없다.
 *
 * <h3>TB_PB209 구조 (이 프로젝트 = 웹 전용 구조)</h3>
 * <pre>
 *   PK      : id (identity). 유니크 인덱스가 없어 사원·연도당 여러 행을 둘 수 있다
 *   연 단위 행 : reqdate = 정산년도 + '0000', hflag = '0'
 *               이월·조정, 그리고 1년 이상 근속자의 연차. 사원·연도당 1행
 *   월차 발생행 : reqdate = 발생일(대상월 말일), hflag = '1', holinum = 1
 *               frdate~todate = 어느 달 개근으로 받은 1일인지. 개근월마다 1행
 *   ewolnum : 이월일수   (직전 연도 잔여를 전액 넘긴 값. 연 단위 행에만 있다)
 *   holinum : 발생일수   (연 단위 행이면 근속표 연차, 월차 발생행이면 늘 1)
 *   monthnum: 조정일수   (가감. 도입 시 기사용분은 음수, 특별부여는 양수)
 *   restnum : 잔여일수   = ewolnum + holinum + monthnum  (저장 시점 기준)
 *   remark  : 조정 이력  — 컬럼이 없어도 동작한다 (_backup/20261106_actas_pb209_remark_*.sql)
 * </pre>
 * elv 는 PB 원본 구조(custcd + spjangcd + hyear + hseq + perid, hactcd '08', hseq '00')라서
 * 키와 사번 형식이 다르다. 이쪽은 personid(숫자)로 person 과 직접 붙으므로
 * elv 가 겪은 'p001' vs person.id 이중 형식 문제가 없다.
 *
 * <h3>잔여는 저장값을 믿지 않고 늘 다시 계산한다</h3>
 * <pre>
 *   잔여 = 이월 + 발생 + 조정 − 사용
 * </pre>
 * 사용은 TB_PB204(휴가신청) 확정분(fixflag='1') 중 <b>연차를 차감하는 휴가구분만</b>
 * (TB_PB210.yearflag='1' — 이 DB 에서는 04 반차·08 연차) 정산년도 안의 것을 센다.
 * TB_PB209.restnum 은 생성·조정 시점의 스냅샷일 뿐이라 휴가가 한 건 들어오면 바로 어긋난다.
 * TB_PB204.yearflag 는 옛 행이 NULL 로 비어 있어(실측: workcd '08' 중 3건) 쓰지 않고,
 * 마스터인 TB_PB210.yearflag 를 본다.
 */
@Slf4j
@Service
public class ClockYearlyService {

    /** 발생행 표시 — reqdate 는 정산년도 + 이 값 */
    private static final String BASE_MMDD = "0000";
    /** 1년 미만 월차 상한 — 근로기준법 제60조 제2항 */
    private static final int MONTHLY_MAX_DAYS = 11;
    /** remark 길이 — 넘치면 오래된 이력부터 지운다 */
    private static final int REMARK_MAX = 255;
    /** 조정일수 입력 허용 범위 (monthnum numeric(5,2)) */
    private static final BigDecimal ADJUST_LIMIT = new BigDecimal("999.9");

    /* 발생행 종류 — hflag 로 가린다.
       예전에는 사원·연도당 1행만 쓰고 월차를 그 해 총액으로 눌러 담았다. 그래서 근로기준법
       제60조 제2항이 말하는 "1개월 개근 시 1일"의 발생 시점이 데이터에 남지 않았고,
       입사연도는 아예 생성조차 되지 않았다. 지금은 월차를 1일씩 행으로 쌓는다.
       테이블이 막은 적은 없다 — PK 는 id 하나뿐이고 유니크 인덱스가 없어 DDL 없이 된다. */
    /** 연 단위 행 — 이월·조정, 그리고 1년 이상 근속자의 연차. reqdate = 정산년도 + '0000' */
    private static final String HFLAG_YEAR = "0";
    /** 월차 발생행 — 1건이 1일. reqdate = 발생일(대상월 말일) */
    private static final String HFLAG_MONTHLY = "1";

    @Autowired
    SqlRunner sqlRunner;

    /** remark 컬럼 유무. DDL 적용 여부를 매번 묻지 않도록 한 번만 조회해 둔다 */
    private Boolean remarkColumn = null;

    // ------------------------------------------------------------------ 조회

    /**
     * 연차 현황 목록.
     *
     * @param year     정산년도 (yyyy)
     * @param name     사원명 (부분일치)
     * @param spjangcd 사업장코드
     * @param rtflag   재직구분 (person.rtflag — 0 재직 / 1 퇴직 / 2 휴직). 빈값이면 전체
     */
    public List<Map<String, Object>> getYearlyList(String year, String name, String spjangcd, String rtflag) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("reqdate", year + BASE_MMDD);

        /* 발생행은 조회년도 1행으로 못 박는다. 예전에는 personid 별 MAX(reqdate) 를 붙여서
           조회년도를 바꿔도 늘 가장 최근 연도 숫자가 나왔다(2025·2026 둘 다 있으면 항상 2026).
           사용일수도 연도·휴가구분 필터가 없어 전 연도 누적이었고 병가·지각까지 연차에서 깎였다. */
        StringBuilder sql = new StringBuilder("""
                SELECT ROW_NUMBER() OVER (ORDER BY p.jik_id, p.id) AS rownum,
                       p.id,
                       p."Name"                 AS person_name,
                       s."Value"                AS jik_id,
                       p.rtdate,
                       p.rtflag,
                       COALESCE(g.ewolnum,  0)  AS ewolnum,
                       COALESCE(g.holinum,  0)  AS holinum,
                       COALESCE(g.monthnum, 0)  AS monthnum,
                       COALESCE(u.daynum,   0)  AS daynum,
                       CASE WHEN COALESCE(g.rowcnt, 0) = 0 THEN NULL
                            ELSE COALESCE(g.ewolnum, 0) + COALESCE(g.holinum, 0)
                               + COALESCE(g.monthnum, 0) - COALESCE(u.daynum, 0)
                       END                      AS restnum
                  FROM person p
                  LEFT JOIN (
                        SELECT "Code", "Value" FROM sys_code WHERE "CodeType" = 'jik_type'
                  ) s ON s."Code" = p.jik_id
                  /* 발생은 그 해 모든 발생행의 합이다. 월차가 1일씩 여러 행으로 쌓이므로
                     한 행만 집어 오면 입사 1~2년차의 발생일수가 통째로 빠진다.
                     이월·조정은 연 단위 행(hflag='0')에만 있으므로 그 행에서만 더한다.
                     연 1행만 있던 기존 데이터도 같은 결과가 나온다 — 읽는 쪽은 호환된다. */
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(x.holinum, 0)) AS holinum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.ewolnum, 0) ELSE 0 END)  AS ewolnum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.monthnum, 0) ELSE 0 END) AS monthnum,
                               COUNT(*) AS rowcnt
                          FROM tb_pb209 x
                         WHERE x.spjangcd = p.spjangcd
                           AND x.personid = p.id
                           AND LEFT(x.reqdate, 4) = :year
                  ) g ON TRUE
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(v.daynum, 0)) AS daynum
                          FROM tb_pb204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.spjangcd
                                         AND w.workcd   = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.spjangcd = p.spjangcd
                           AND v.personid = p.id
                           AND COALESCE(v.fixflag, '') = '1'
                           AND v.reqdate >= :year || '0101'
                           AND v.reqdate <= :year || '1231'
                  ) u ON TRUE
                 WHERE p.spjangcd = :spjangcd
                """);

        if (rtflag != null && !rtflag.isBlank()) {
            sql.append("   AND COALESCE(p.rtflag, '') = :rtflag\n");
            dicParam.addValue("rtflag", rtflag.trim());
        }
        if (name != null && !name.isBlank()) {
            sql.append("   AND p.\"Name\" LIKE '%' || :name || '%'\n");
            dicParam.addValue("name", name.trim());
        }
        sql.append(" ORDER BY p.jik_id, p.id\n");

        return nvl(this.sqlRunner.getRows(sql.toString(), dicParam));
    }

    /**
     * 사원 1명의 연차 발생·사용 내역.
     *
     * 첫 행이 발생('생성')이고 그 아래가 사용내역이다. 각 사용행의 잔여는
     * 발생 기준액에서 그 시점까지의 누적 사용분을 뺀 값이며, 연차를 차감하지 않는
     * 휴가구분(yearflag <> '1')은 누적에서 빠지고 잔여를 비워 둔다.
     */
    public List<Map<String, Object>> getYearlyDetail(Integer id, String year, String spjangcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("id", id);
        dicParam.addValue("year", year);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("reqdate", year + BASE_MMDD);

        String remark = hasRemarkColumn() ? "g.remark" : "CAST(NULL AS varchar(255)) AS remark";

        String sql = """
                WITH
                /* 연 단위 행 — 이월·조정·1년이상 연차. 조정 이력(remark)도 여기 있다 */
                yearrow AS (
                    SELECT COALESCE(g.ewolnum, 0) AS ewolnum,
                           COALESCE(g.holinum, 0) AS holinum,
                           COALESCE(g.monthnum, 0) AS monthnum,
                           %s
                      FROM tb_pb209 g
                     WHERE g.personid = :id
                       AND g.spjangcd = :spjangcd
                       AND g.reqdate  = :reqdate
                ),
                /* 월차 발생행 — 개근월마다 1건. 상세에 월별로 나열한다 */
                monthrows AS (
                    SELECT g.reqdate, g.frdate, g.todate, COALESCE(g.holinum, 0) AS holinum
                      FROM tb_pb209 g
                     WHERE g.personid = :id
                       AND g.spjangcd = :spjangcd
                       AND LEFT(g.reqdate, 4) = :year
                       AND COALESCE(g.hflag, '0') = '1'
                ),
                /* 기준액은 그 해 발생 전체의 합이다 — 월차가 여러 행이라 합계로 읽어야 한다 */
                gen AS (
                    SELECT COALESCE((SELECT ewolnum  FROM yearrow), 0) AS ewolnum,
                           COALESCE((SELECT holinum  FROM yearrow), 0)
                               + COALESCE((SELECT SUM(holinum) FROM monthrows), 0) AS holinum,
                           COALESCE((SELECT monthnum FROM yearrow), 0) AS monthnum,
                           COALESCE((SELECT ewolnum  FROM yearrow), 0)
                               + COALESCE((SELECT holinum FROM yearrow), 0)
                               + COALESCE((SELECT SUM(holinum) FROM monthrows), 0)
                               + COALESCE((SELECT monthnum FROM yearrow), 0) AS basenum,
                           (SELECT remark FROM yearrow) AS remark
                     WHERE EXISTS (SELECT 1 FROM yearrow)
                        OR EXISTS (SELECT 1 FROM monthrows)
                ),
                uses AS (
                    SELECT v.reqdate, v.frdate, v.todate, v.daynum, v.workcd, w.worknm,
                           w.yearflag, v.id AS vid,
                           SUM(CASE WHEN w.yearflag = '1' THEN COALESCE(v.daynum, 0) ELSE 0 END)
                               OVER (ORDER BY v.reqdate, v.id
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum_daynum
                      FROM tb_pb204 v
                      JOIN tb_pb210 w ON w.spjangcd = v.spjangcd AND w.workcd = v.workcd
                     WHERE v.personid = :id
                       AND v.spjangcd = :spjangcd
                       AND COALESCE(v.fixflag, '') = '1'
                       AND v.reqdate >= :year || '0101'
                       AND v.reqdate <= :year || '1231'
                ),
                unioned AS (
                    /* 발생행의 '신청일'은 발생일(회계연도 시작일)로 보여 준다.
                       저장된 reqdate 는 'yyyy0000' 이라 그대로 내보내면 화면에 2026-00-00 으로 찍힌다 */
                    SELECT :year || '0101'                AS reqdate,
                           :year || '0101'                AS frdate,
                           :year || '1231'                AS todate,
                           CAST(NULL AS numeric(5,2))     AS daynum,
                           CAST(NULL AS varchar(2))       AS workcd,
                           '생성'                          AS worknm,
                           g.ewolnum, g.holinum, g.monthnum,
                           g.basenum                      AS restnum,
                           g.remark,
                           0                              AS ord
                      FROM gen g
                    UNION ALL
                    /* 월차 발생을 달별로 보여 준다. 잔여는 비운다 — 이 행은 부여이고,
                       누적 잔여는 아래 사용행에서 기준액 기준으로 계산된다 */
                    SELECT m.reqdate, m.frdate, m.todate,
                           CAST(NULL AS numeric(5,2))     AS daynum,
                           CAST(NULL AS varchar(2))       AS workcd,
                           '월차발생'                       AS worknm,
                           CAST(0 AS numeric(5,2))        AS ewolnum,
                           m.holinum,
                           CAST(0 AS numeric(5,2))        AS monthnum,
                           CAST(NULL AS numeric(5,2))     AS restnum,
                           CAST(NULL AS varchar(255))     AS remark,
                           0                              AS ord
                      FROM monthrows m
                    UNION ALL
                    SELECT u.reqdate, u.frdate, u.todate, u.daynum, u.workcd, u.worknm,
                           CAST(0 AS numeric(5,2))        AS ewolnum,
                           CAST(0 AS numeric(5,2))        AS holinum,
                           CAST(0 AS numeric(5,2))        AS monthnum,
                           CASE WHEN u.yearflag = '1'
                                THEN (SELECT MAX(basenum) FROM gen) - u.cum_daynum
                                ELSE NULL END             AS restnum,
                           CAST(NULL AS varchar(255))     AS remark,
                           1                              AS ord
                      FROM uses u
                )
                SELECT ROW_NUMBER() OVER (ORDER BY ord, reqdate) - 1 AS rownum, *
                  FROM unioned
                 ORDER BY ord, reqdate
                """.formatted(remark);

        return nvl(this.sqlRunner.getRows(sql, dicParam));
    }

    // ------------------------------------------------------- 생성(미리계산)

    /**
     * 연차·월차 생성(미리계산). 버튼 하나로 근속 1년 이상(연차)과 1년 미만(월차)을 함께 만든다.
     *
     * 예전에는 「연차생성」·「월차생성」 버튼이 따로였고, 각각 다른 SQL 이 돌면서
     * 조회 결과를 묻지도 않고 바로 저장했다. 지금은 계산 결과를 그리드에 먼저 보여주고
     * 사용자가 확인한 뒤 저장한다.
     *
     * <h3>분기</h3>
     * <pre>
     *   k = 정산년 − 입사년 − 1     (회계연도 기준. 1월 1일 시점의 완성 계속근로연수)
     *   k == -1 → 월차 (입사연도. 입사 다음 달부터 그 해 12월까지의 개근월)
     *   k == 0  → 월차 (직전년도 입사자. 만 1년 되는 달의 직전 달까지)
     *   k &gt;= 1  → 연차 (근속표 tenureDays(k * 12))
     * </pre>
     * <b>입사연도(k = −1)도 대상이다.</b> 예전에는 {@code if (k < 0) continue;} 로 걸러내
     * 입사한 해에는 월차가 한 건도 생기지 않았다. 근로기준법 제60조 제2항은 입사 1개월
     * 뒤부터 1일씩 발생한다고 보므로 정우철(2024-06-24)은 2024년 말에 이미 6일을 갖고
     * 있어야 했는데, 시스템상 2024년 보유는 0 이었다.
     *
     * 월차는 1년 미만 구간(입사 다음 달 ~ 만 1년 되는 달의 직전 달, 정확히 11개월)의
     * 개근월을 구해 놓고 <b>그 중 정산년도에 속하는 달만</b> 그 해 발생분으로 삼는다.
     * 그래서 해마다 끊기고, 상한 11 은 두 해를 합쳐서 지켜진다.
     * 저장은 개근월마다 1행이다 — {@link #saveMonthlyRows} 참고.
     * k 를 이렇게 세는 것이 핵심이다. 예전 구현은 {@code AGE(전년 12/31, 입사일)} 로 실제
     * 경과월수를 구해 PB 근속표에 넣었는데, 그 표의 경계(&le;24, &le;48 …)는 {@code k * 12} 를
     * 전제로 깎여 있어서 실경과월을 넣으면 경계를 한 칸 일찍 넘는다. 예를 들어 2019-11-15
     * 입사자를 2026년도로 돌리면 실경과 73개월 → 18일이 나오지만, 법정(제60조 ④
     * {@code 15 + (k-1)/2}, k=6)은 17일이다. elv 는 운영 111명 전원으로 {@code k * 12} 가
     * 법과 일치함을 확인했다.
     *
     * @param baseYm     기준일자 (yyyy-MM / yyyyMM). 월차 개근월수를 셀 끝 지점. 빈값이면 정산년도 12월
     * @param createFlag '1' 기존 발생분까지 다시 계산 / '2' 신규만
     */
    public List<Map<String, Object>> createAll(String year, String spjangcd, String baseYm,
                                               String name, String rtflag, String createFlag) {

        int settleYear = Integer.parseInt(year);
        int[] base = parseYm(baseYm, settleYear);
        int baseYear = base[0], baseMonth = base[1];

        // 직전 연도 잔여 = 이월액. 발생행이 없는 사원은 키가 없어 0 이 된다
        Map<Integer, BigDecimal> carry = prevRemains(settleYear, spjangcd);
        // 월차 대상자의 개근월 목록 (1년 미만 구간 전체. 연도로 나누는 것은 아래에서 한다)
        Map<Integer, List<String>> fullAttend = fullAttendanceMonths(spjangcd, baseYear, baseMonth);
        // 올해 이미 쓴 연차 — 미리보기의 '사용일'·'잔여일' 표시용. 저장되는 값은 아니다
        Map<Integer, BigDecimal> used = usedDays(settleYear, spjangcd);

        boolean regenerate = "1".equals(createFlag);
        int negatives = 0;

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map<String, Object> t : getTargets(year, spjangcd, name, rtflag)) {

            Integer personid = intOf(t.get("id"));
            String rtdate = str(t.get("rtdate"));
            if (personid == null || rtdate.length() < 4) continue;

            int entYear;
            try { entYear = Integer.parseInt(rtdate.substring(0, 4)); } catch (Exception e) { continue; }

            int k = settleYear - entYear - 1;
            if (entYear > settleYear) continue;    // 정산년도보다 늦게 입사한 사원
            /* 1년을 못 채운 구간은 월차다. 입사연도(k = −1)도 대상이다 — 예전에는
               {@code if (k < 0) continue;} 로 걸러내서 입사한 해에는 월차가 한 건도
               생기지 않았다. 제60조 제2항은 입사 1개월 뒤부터 발생한다고 보므로,
               정우철(2024-06-24)은 2024년 말에 이미 6일을 갖고 있어야 했다. */
            boolean monthly = (k <= 0);

            BigDecimal holinum;
            long months;
            List<String> myMonths = List.of();
            if (monthly) {
                /* 1년 미만 구간의 개근월 중 이 정산년도에 속하는 달만 올해 발생분이다.
                   해마다 끊기므로 입사연도·다음연도에 나눠 들어간다. */
                String prefix = String.valueOf(settleYear);
                myMonths = fullAttend.getOrDefault(personid, List.<String>of()).stream()
                        .filter(ym -> ym.startsWith(prefix))
                        .toList();
                months = myMonths.size();
                holinum = monthlyDays(months);
            } else {
                months = (long) k * 12;
                holinum = tenureDays(months);
            }
            if (holinum.compareTo(BigDecimal.ZERO) <= 0) continue;

            /* 이월 — 직전 연도 잔여를 전액 넘긴다 (한도 없음).
               잔여가 음수인 사람(발생분보다 많이 쓴 경우)은 0 으로 둔다. 선사용분을 다음 해
               연차에서 깎는 것은 근로자에게 불이익이라 정책 결정 없이 자동으로 하지 않는다.
               대신 몇 명인지 로그에 남긴다. */
            BigDecimal ewolnum = carry.getOrDefault(personid, BigDecimal.ZERO);
            if (ewolnum.signum() < 0) {
                negatives++;
                ewolnum = BigDecimal.ZERO;
            }

            BigDecimal monthnum = dec(t.get("cur_monthnum"));   // 이미 들어가 있는 조정일수는 유지

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", personid);
            row.put("person_name", t.get("person_name"));
            row.put("jik_id", t.get("jik_id"));
            row.put("rtdate", rtdate);
            row.put("ewolnum", ewolnum);
            row.put("holinum", holinum);
            row.put("monthnum", monthnum);
            /* 사용일은 생성과 무관하지만 미리보기에서는 보여줘야 한다. 머리말이
               「잔여 = 이월 + 발생 + 조정 − 사용」이라 0 으로 두면 이미 연차를 쓴 사원의
               잔여가 실제보다 많아 보인다. TB_PB209.restnum 에는 들어가지 않는다 —
               그 컬럼은 부여총액 스냅샷이고, 화면·모바일의 잔여는 늘 다시 계산한다. */
            BigDecimal daynum = used.getOrDefault(personid, BigDecimal.ZERO);
            row.put("daynum", daynum);
            row.put("restnum", ewolnum.add(holinum).add(monthnum).subtract(daynum));
            row.put("months", months);
            row.put("monthly", monthly ? "1" : "0");
            row.put("gubun", monthly ? "월차" : "연차");
            /* 월차를 1일씩 행으로 쌓으려면 어느 달분인지 저장 쪽에 넘겨야 한다.
               화면은 이 값을 쓰지 않지만 그리드가 행 객체를 그대로 돌려보내므로 같이 간다. */
            row.put("monthlist", String.join(",", myMonths));
            row.put("exists", t.get("already"));
            rows.add(row);
        }

        // '2' 신규만 등록 → 이미 발생행이 있는 사원은 목록에서 뺀다
        if (!regenerate) {
            rows.removeIf(r -> {
                Object already = r.get("exists");
                return already != null && !"0".equals(String.valueOf(already));
            });
        }

        if (negatives > 0) {
            log.warn("직전 연도 잔여가 음수인 사원 {}명은 이월을 0 으로 두었습니다. spjangcd={} year={}",
                    negatives, spjangcd, year);
        }

        int no = 1;
        for (Map<String, Object> r : rows) {
            r.remove("exists");
            r.put("rownum", no++);
        }
        return rows;
    }

    /**
     * 직전 연도 잔여 — 전액 이월(ewolnum)로 넘길 값. 키는 personid.
     *
     * 예전에는 직전 연도 행의 <b>저장된 restnum 을 그대로</b> 읽었다. 그 값은 생성 시점
     * 스냅샷(= 이월 + 발생 + 조정)이라 그 해에 쓴 연차가 빠지지 않은 금액이다.
     * 그래서 쓴 만큼이 매년 이월로 되살아나 잔여가 과다하게 누적됐다.
     * 지금은 사용분을 뺀 실제 잔여를 다시 계산한다.
     *
     * 직전 기간은 <b>hyear = 정산년 − 1</b> 로 못 박는다. "가장 최근 발생행"을 찾으면
     * 연차생성을 수년간 안 돌린 경우 몇 해 전 잔여가 한꺼번에 들어와 숫자가 폭발한다.
     * 공백 연도가 있으면 이월은 0 이고, 연속 운영부터 쌓인다.
     */
    private Map<Integer, BigDecimal> prevRemains(int settleYear, String spjangcd) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("prevyear", String.valueOf(settleYear - 1));
        p.addValue("reqdate", (settleYear - 1) + BASE_MMDD);

        /* 작년 발생행을 사원별로 합친다. 월차는 1일씩 여러 행이라 합계로 읽어야 하고,
           이월·조정은 연 단위 행(hflag='0')에만 있다. */
        String sql = """
                SELECT g.personid,
                       SUM(CASE WHEN COALESCE(g.hflag, '0') = '0'
                                THEN COALESCE(g.ewolnum, 0) + COALESCE(g.monthnum, 0)
                                ELSE 0 END)
                           + SUM(COALESCE(g.holinum, 0))
                           - COALESCE(MAX(u.daynum), 0) AS restnum
                  FROM tb_pb209 g
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(v.daynum, 0)) AS daynum
                          FROM tb_pb204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.spjangcd
                                         AND w.workcd   = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.spjangcd = g.spjangcd
                           AND v.personid = g.personid
                           AND COALESCE(v.fixflag, '') = '1'
                           AND v.reqdate >= :prevyear || '0101'
                           AND v.reqdate <= :prevyear || '1231'
                  ) u ON TRUE
                 WHERE g.spjangcd = :spjangcd
                   AND LEFT(g.reqdate, 4) = :prevyear
                 GROUP BY g.personid
                """;

        Map<Integer, BigDecimal> out = new HashMap<>();
        try {
            for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
                Integer pid = intOf(r.get("personid"));
                if (pid != null) out.put(pid, dec(r.get("restnum")));
            }
        } catch (Exception e) {
            // 이월을 못 읽어도 생성 자체는 되게 한다. 조용히 0 이 되면 안 되므로 로그는 남긴다
            log.error("직전 연도 잔여를 읽지 못해 이월을 0 으로 둡니다. spjangcd={} prevyear={}",
                    spjangcd, settleYear - 1, e);
        }
        return out;
    }

    /**
     * 해당 연도에 확정된 연차 사용일수. 키는 personid.
     *
     * 연차를 깎는 휴가구분(TB_PB210.yearflag='1')만 세고, 결재가 끝난 건
     * (TB_PB204.fixflag='1')만 센다. 현황 조회·모바일의 잔여 계산과 같은 기준이다.
     */
    private Map<Integer, BigDecimal> usedDays(int settleYear, String spjangcd) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("year", String.valueOf(settleYear));

        String sql = """
                SELECT v.personid,
                       SUM(COALESCE(v.daynum, 0)) AS daynum
                  FROM tb_pb204 v
                  JOIN tb_pb210 w ON w.spjangcd = v.spjangcd
                                 AND w.workcd   = v.workcd
                                 AND w.yearflag = '1'
                 WHERE v.spjangcd = :spjangcd
                   AND COALESCE(v.fixflag, '') = '1'
                   AND v.reqdate >= :year || '0101'
                   AND v.reqdate <= :year || '1231'
                 GROUP BY v.personid
                """;

        Map<Integer, BigDecimal> out = new HashMap<>();
        try {
            for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
                Integer pid = intOf(r.get("personid"));
                if (pid != null) out.put(pid, dec(r.get("daynum")));
            }
        } catch (Exception e) {
            // 표시용이라 못 읽어도 생성은 계속한다. 다만 조용히 0 이 되면 안 되므로 로그는 남긴다
            log.error("올해 사용일수를 읽지 못해 미리보기 '사용일'을 0 으로 둡니다. spjangcd={} year={}",
                    spjangcd, settleYear, e);
        }
        return out;
    }

    /**
     * 1년 미만 근로자의 개근월수 — 근로기준법 제60조 제2항("1개월 개근 시 1일").
     *
     * 입사 다음 달부터 <b>만 1년이 되는 달의 직전 달</b>까지(기준월을 넘지 않는 선에서) 훑어,
     * 그 달의 근태집계(TB_PB203)에 <b>결근</b>이 없으면 개근으로 센다.
     * 지각·조퇴는 개근을 깨지 않는다 — 소정근로일에 출근한 날이므로 결근이 아니다
     * (2026-11-09 사용자 확인). 예전에는 jitime·jotime 까지 합산해서 지각 한 번에
     * 그 달 월차가 날아갔다. 실측에서 김유리·이재욱이 그래서 11 이 아니라 8 이었다.
     * <b>집계 자체가 없는 달도 개근으로 본다</b> — 근태를 아직 입력하지 않았다는 뜻이고,
     * 그것을 결근으로 보면 입력이 늦은 달만큼 연차가 사라진다.
     *
     * 예전 구현에서 고친 것 두 가지.
     *   - 상한이 15 였다 → 11 (제60조 제2항의 한도)
     *   - 대상자 필터에 {@code '20251231'} 이 하드코딩돼 있어 해가 바뀌면 틀렸다 → 제거
     *     (대상자 판정은 {@link #createAll} 의 k 가 하므로 여기서는 사원을 가리지 않는다)
     *   - 예전에는 {@code AGE(기준일, 입사일) < 1년} 인 사원만 목록에 띄웠다. 그래서 연중에
     *     한 번 돌리고 나면 만 1년이 지난 뒤에는 남은 달의 월차를 다시 잡을 길이 없었다.
     *     지금은 k 로 판정하고 기간은 아래 WHERE 로 끊는다.
     */
    private Map<Integer, List<String>> fullAttendanceMonths(String spjangcd, int baseYear, int baseMonth) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd", spjangcd);
        p.addValue("baseym", String.format("%04d%02d", baseYear, baseMonth));

        String sql = """
                WITH emp AS (
                    SELECT p.id AS personid,
                           TO_DATE(p.rtdate, 'YYYYMMDD') AS entdate
                      FROM person p
                     WHERE p.spjangcd = :spjangcd
                       AND p.rtdate ~ '^[0-9]{8}$'
                ),
                months AS (
                    SELECT e.personid,
                           TO_CHAR(gs, 'YYYYMM') AS ym
                      FROM emp e
                      CROSS JOIN LATERAL generate_series(
                            DATE_TRUNC('month', e.entdate) + INTERVAL '1 month',
                            DATE_TRUNC('month', TO_DATE(:baseym || '01', 'YYYYMMDD')),
                            INTERVAL '1 month'
                      ) AS gs
                     /* 만 1년이 되는 달부터는 월차 대상이 아니다 (제60조 제2항은 "1년 미만").
                        이 조건이 없으면 1년이 지난 뒤의 개근월이 1년 안의 결근월을 메워
                        상한 11 을 억지로 채운다. 달 단위로 끊으면 후보는 늘 정확히 11 개월이다. */
                     WHERE gs < DATE_TRUNC('month', e.entdate + INTERVAL '1 year')
                )
                SELECT m.personid,
                       STRING_AGG(m.ym, ',' ORDER BY m.ym) AS yms
                  FROM months m
                  LEFT JOIN tb_pb203 a
                         ON a.spjangcd = :spjangcd
                        AND a.personid = m.personid
                        AND a.workym   = m.ym
                 /* 결근만 본다. 지각·조퇴는 출근한 날이라 개근을 깨지 않는다 */
                 WHERE COALESCE(a.abtime, 0) = 0
                 GROUP BY m.personid
                """;

        Map<Integer, List<String>> out = new HashMap<>();
        try {
            for (Map<String, Object> r : nvl(this.sqlRunner.getRows(sql, p))) {
                Integer pid = intOf(r.get("personid"));
                if (pid == null) continue;
                String yms = str(r.get("yms"));
                if (yms.isEmpty()) continue;
                List<String> list = new ArrayList<>(Arrays.asList(yms.split(",")));
                /* 상한 11 은 1년 미만 구간 전체에 걸린 상한이다(제60조 제2항). 후보 구간이
                   정확히 11 개월이라 보통은 걸리지 않지만, 앞에서부터 잘라 두면 해를
                   나눠 생성해도 합계가 11 을 넘지 않는다. */
                if (list.size() > MONTHLY_MAX_DAYS) list = list.subList(0, MONTHLY_MAX_DAYS);
                out.put(pid, list);
            }
        } catch (Exception e) {
            log.error("개근월을 읽지 못해 월차를 0 으로 둡니다. spjangcd={} baseym={}-{}",
                    spjangcd, baseYear, baseMonth, e);
        }
        return out;
    }

    /** 생성 대상자 (입사일 보유). rtflag 가 비면 퇴직자만 제외한다 */
    private List<Map<String, Object>> getTargets(String year, String spjangcd, String name, String rtflag) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("year", year);
        dicParam.addValue("reqdate", year + BASE_MMDD);

        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p."Name" AS person_name, p.rtdate, p.jik_id AS jik_cd,
                       s."Value" AS jik_id,
                       g.monthnum AS cur_monthnum,
                       /* 「신규만 등록」의 판정. 월차 발생행만 있어도 이미 생성된 해로 본다 */
                       CASE WHEN COALESCE(a.rowcnt, 0) = 0 THEN 0 ELSE 1 END AS already
                  FROM person p
                  LEFT JOIN (
                        SELECT "Code", "Value" FROM sys_code WHERE "CodeType" = 'jik_type'
                  ) s ON s."Code" = p.jik_id
                  /* 조정일수는 연 단위 행에만 있다 */
                  LEFT JOIN tb_pb209 g
                         ON g.personid = p.id
                        AND g.spjangcd = p.spjangcd
                        AND g.reqdate  = :reqdate
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS rowcnt
                          FROM tb_pb209 x
                         WHERE x.spjangcd = p.spjangcd
                           AND x.personid = p.id
                           AND LEFT(x.reqdate, 4) = :year
                  ) a ON TRUE
                 WHERE p.spjangcd = :spjangcd
                   AND p.rtdate ~ '^[0-9]{8}$'
                """);

        if (rtflag != null && !rtflag.isBlank()) {
            sql.append("   AND COALESCE(p.rtflag, '') = :rtflag\n");
            dicParam.addValue("rtflag", rtflag.trim());
        } else {
            // 재직구분을 고르지 않았으면 퇴직자만 뺀다. 휴직자와 구분이 비어 있는 사원은 대상이다
            sql.append("   AND COALESCE(p.rtflag, '') <> '1'\n");
        }
        if (name != null && !name.isBlank()) {
            sql.append("   AND p.\"Name\" LIKE '%' || :name || '%'\n");
            dicParam.addValue("name", name.trim());
        }
        sql.append(" ORDER BY p.jik_id, p.id\n");

        return nvl(this.sqlRunner.getRows(sql.toString(), dicParam));
    }

    // ------------------------------------------------------------------ 저장

    /**
     * 발생행 저장. createFlag '1' = 이미 있어도 다시 계산, '2' = 신규만 등록.
     *
     * 다시 생성해도 손으로 넣은 <b>조정일수(monthnum)와 그 이력(remark)은 건드리지 않는다.</b>
     * 발생일수·이월일수만 갱신하고 잔여는 그 조정일수를 포함해 다시 계산한다.
     * 조정일수를 쓰기 시작한 뒤 누군가 「기존 생성분까지 다시 계산」을 누르면 보정이
     * 전부 날아가는 것을 막기 위한 것이다.
     *
     * 저장되는 restnum 은 <b>부여총액(이월 + 발생 + 조정)</b> 이다. 미리보기 그리드의
     * 잔여일에서 뺀 '사용일'은 넣지 않는다 — 휴가가 한 건 확정되면 바로 어긋날 값이고,
     * 화면·모바일의 잔여는 어차피 매번 다시 계산하기 때문이다.
     * 다시 생성해도 이월은 올해 행이 아니라 작년 행에서 계산하므로 몇 번 돌려도 같은 결과가 된다.
     *
     * <h3>월차는 1일씩 행으로 쌓는다</h3>
     * 근로기준법 제60조 제2항의 월차는 1개월 개근마다 1일씩 발생한다. 그래서 개근월
     * 하나당 행 하나를 넣는다 ({@code hflag='1'}, {@code reqdate}=대상월 말일,
     * {@code frdate}~{@code todate}=대상월, {@code holinum}=1).
     * 이월·조정은 연 단위 행({@code hflag='0'}, {@code reqdate=yyyy0000})에만 두고,
     * 월차만 있는 해에도 그 행을 0 으로 만들어 둔다 — 조정일수를 넣을 자리가 필요하다.
     *
     * 월차 발생행은 <b>이미 있는 달은 건너뛰고 없는 달만 넣는다.</b> 지우지 않는다.
     * 「기존 생성분까지 다시 계산」을 눌러도 지난 달의 발생 기록은 남는다 — 발생은
     * 그 시점의 사실이고, 뒤늦게 근태가 바뀌어 일수를 줄여야 하면 조정일로 처리한다.
     */
    public int saveGenerated(List<Map<String, Object>> rows, String year, String spjangcd, String createFlag) {

        if (rows == null || rows.isEmpty()) return 0;

        boolean regenerate = "1".equals(createFlag);
        int saved = 0;

        for (Map<String, Object> row : rows) {

            Integer personid = intOf(row.get("id"));
            if (personid == null) continue;

            boolean monthly = "1".equals(str(row.get("monthly")));

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("spjangcd", spjangcd);
            p.addValue("personid", personid);
            p.addValue("reqdate", year + BASE_MMDD);

            Map<String, Object> cur = this.sqlRunner.getRow("""
                    SELECT id, monthnum FROM tb_pb209
                     WHERE personid = :personid AND spjangcd = :spjangcd AND reqdate = :reqdate
                    """, p);

            if (cur != null && !regenerate) continue;   // '2' 신규만 등록

            BigDecimal ewolnum = dec(row.get("ewolnum"));
            /* 연 단위 행의 발생일수는 1년 이상 근속자의 연차뿐이다. 월차는 아래에서
               달마다 따로 행을 넣으므로 여기서는 0 으로 둔다 — 안 그러면 이중 계산된다. */
            BigDecimal holinum = monthly ? BigDecimal.ZERO.setScale(1) : dec(row.get("holinum"));
            // 이미 있는 행이면 그 조정일수를 그대로 쓴다. 새 행이면 0 으로 시작한다
            BigDecimal monthnum = (cur != null) ? dec(cur.get("monthnum")) : dec(row.get("monthnum"));

            p.addValue("ewolnum", ewolnum);
            p.addValue("holinum", holinum);
            p.addValue("monthnum", monthnum);
            // 월차분까지 더한 부여총액. 집계 조회는 이 값을 쓰지 않지만 스냅샷으로 남긴다
            p.addValue("restnum", ewolnum.add(dec(row.get("holinum"))).add(monthnum));

            if (cur != null) {
                p.addValue("id", intOf(cur.get("id")));
                this.sqlRunner.execute("""
                        UPDATE tb_pb209
                           SET ewolnum = :ewolnum, holinum = :holinum,
                               monthnum = :monthnum, restnum = :restnum
                         WHERE id = :id
                        """, p);
            } else {
                this.sqlRunner.execute("""
                        INSERT INTO tb_pb209
                               (spjangcd, reqdate, personid, hflag,
                                ewolnum, holinum, monthnum, restnum)
                        VALUES (:spjangcd, :reqdate, :personid, '0',
                                :ewolnum, :holinum, :monthnum, :restnum)
                        """, p);
            }

            if (monthly) saveMonthlyRows(personid, spjangcd, str(row.get("monthlist")));
            saved++;
        }
        return saved;
    }

    /**
     * 월차 발생행을 개근월마다 1건씩 넣는다 (근로기준법 제60조 제2항 "1개월 개근 시 1일").
     *
     * <pre>
     *   reqdate  대상월 말일 = 발생일
     *   frdate   대상월 1일  ┐ 어느 달 개근으로 받은 1일인지
     *   todate   대상월 말일 ┘
     *   holinum  1
     *   hflag    '1'
     * </pre>
     *
     * 발생일을 응당일(입사일과 같은 날)이 아니라 <b>대상월 말일</b>로 두는 이유는
     * 개근 판정 자료인 TB_PB203 이 달력월(workym) 집계여서다. 응당일로 끊으려면
     * 두 달에 걸친 구간(예 6/24~7/23)을 판정해야 하는데 그 자료가 없다.
     *
     * 이미 있는 달은 건너뛴다 — 여러 번 눌러도 중복으로 쌓이지 않고, 지우지도 않는다.
     *
     * @param months "202407,202408,..." 형태. 비면 아무 것도 하지 않는다
     */
    private void saveMonthlyRows(Integer personid, String spjangcd, String months) {

        if (months == null || months.isBlank()) return;

        for (String ym : months.split(",")) {
            String m = ym.trim();
            if (m.length() != 6) continue;

            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("spjangcd", spjangcd);
            p.addValue("personid", personid);
            p.addValue("ym", m);

            /* 발생일·대상기간은 DB 가 계산한다. 말일을 자바에서 만들면 윤년·월말 처리를
               두 곳에서 하게 된다. */
            this.sqlRunner.execute("""
                    INSERT INTO tb_pb209
                           (spjangcd, reqdate, personid, hflag,
                            frdate, todate, ewolnum, holinum, monthnum, restnum)
                    SELECT :spjangcd,
                           TO_CHAR((DATE_TRUNC('month', TO_DATE(:ym || '01', 'YYYYMMDD'))
                                    + INTERVAL '1 month - 1 day')::date, 'YYYYMMDD'),
                           :personid, '%s',
                           :ym || '01',
                           TO_CHAR((DATE_TRUNC('month', TO_DATE(:ym || '01', 'YYYYMMDD'))
                                    + INTERVAL '1 month - 1 day')::date, 'YYYYMMDD'),
                           0, 1, 0, 1
                     WHERE NOT EXISTS (
                           SELECT 1 FROM tb_pb209 z
                            WHERE z.spjangcd = :spjangcd
                              AND z.personid = :personid
                              AND COALESCE(z.hflag, '0') = '%s'
                              AND z.frdate = :ym || '01'
                     )
                    """.formatted(HFLAG_MONTHLY, HFLAG_MONTHLY), p);
        }
    }

    // ------------------------------------------------------------ 조정일수

    /**
     * 조정일수(monthnum) 저장. 발생행의 monthnum 만 바꾸고 잔여를 다시 계산한다.
     *
     * <h3>잔여를 직접 받지 않는 이유</h3>
     * 화면의 잔여는 {@code 이월 + 발생 + 조정 − 사용(TB_PB204)} 으로 늘 계산되기 때문에,
     * 잔여를 적어 넣어도 휴가가 한 건만 들어오면 덮인다. 그래서 입력은 구성요소인
     * 조정일수에 받는다. 연차를 새로 도입해 과거 사용분이 시스템에 없는 경우
     * 그 일수를 음수로 넣으면 현황과 모바일 잔여가 둘 다 즉시 맞는다.
     *
     * 발생행이 없는 사원은 저장하지 않고 이름을 돌려준다 — 연차생성이 먼저다.
     *
     * @param actor 이력에 남길 작업자 표시명
     */
    public Map<String, Object> saveAdjust(List<Map<String, Object>> rows, String year,
                                          String spjangcd, String actor) {

        int saved = 0;
        List<String> notGenerated = new ArrayList<>();   // 발생행이 없어 저장 못한 사원
        List<String> outOfRange = new ArrayList<>();     // 허용 범위를 넘은 입력

        boolean useRemark = hasRemarkColumn();

        if (rows != null) {
            for (Map<String, Object> row : rows) {

                Integer personid = intOf(row.get("id"));
                if (personid == null) continue;
                String pernm = str(row.get("person_name"));
                String who = pernm.isEmpty() ? String.valueOf(personid) : pernm;

                BigDecimal after = dec(row.get("monthnum")).setScale(1, RoundingMode.HALF_UP);
                if (after.abs().compareTo(ADJUST_LIMIT) > 0) {
                    outOfRange.add(who);
                    continue;
                }

                MapSqlParameterSource p = new MapSqlParameterSource();
                p.addValue("spjangcd", spjangcd);
                p.addValue("personid", personid);
                p.addValue("reqdate", year + BASE_MMDD);

                Map<String, Object> cur = this.sqlRunner.getRow("""
                        SELECT id, ewolnum, holinum, monthnum%s
                          FROM tb_pb209
                         WHERE personid = :personid AND spjangcd = :spjangcd AND reqdate = :reqdate
                        """.formatted(useRemark ? ", remark" : ""), p);

                // 발생행이 없으면 조정만 따로 만들지 않는다 — 연차생성을 먼저 해야 한다
                if (cur == null) {
                    notGenerated.add(who);
                    continue;
                }

                BigDecimal before = dec(cur.get("monthnum")).setScale(1, RoundingMode.HALF_UP);
                if (before.compareTo(after) == 0) continue;   // 바뀐 게 없다

                p.addValue("id", intOf(cur.get("id")));
                p.addValue("monthnum", after);
                p.addValue("restnum", dec(cur.get("ewolnum")).add(dec(cur.get("holinum"))).add(after));

                if (useRemark) {
                    p.addValue("remark", appendHistory(str(cur.get("remark")), actor, before, after));
                    this.sqlRunner.execute("""
                            UPDATE tb_pb209
                               SET monthnum = :monthnum, restnum = :restnum, remark = :remark
                             WHERE id = :id
                            """, p);
                } else {
                    this.sqlRunner.execute("""
                            UPDATE tb_pb209
                               SET monthnum = :monthnum, restnum = :restnum
                             WHERE id = :id
                            """, p);
                }
                saved++;
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("saved", saved);
        out.put("notGenerated", notGenerated);
        out.put("outOfRange", outOfRange);
        out.put("remarkEnabled", useRemark);
        return out;
    }

    /** remark 에 조정 이력을 덧붙인다. 255자를 넘으면 오래된 것부터 버린다 */
    private String appendHistory(String old, String actor, BigDecimal before, BigDecimal after) {
        String who = (actor == null || actor.isBlank()) ? "-" : actor.trim();
        String entry = "[" + LocalDate.now() + " " + who + "] 조정 " + plain(before) + "→" + plain(after);

        String merged = (old == null || old.isBlank()) ? entry : old.trim() + " / " + entry;
        while (merged.length() > REMARK_MAX) {
            int cut = merged.indexOf(" / ");
            if (cut < 0) {                                   // 한 건만으로도 넘치면 뒤에서 자른다
                merged = merged.substring(merged.length() - REMARK_MAX);
                break;
            }
            merged = merged.substring(cut + 3);
        }
        return merged;
    }

    /**
     * TB_PB209.remark 컬럼이 있는지. 없으면 조정일수는 저장되고 이력만 생략된다.
     *
     * 이 프로젝트는 remark 가 없는 상태로 운영되고 있어서(elv 는 사업체별로 갈린다),
     * DDL 적용 전에 배포돼도 조정 저장이 터지지 않게 분기한다.
     * 결과는 캐시한다 — 컬럼이 중간에 생기면 재시작 후 반영된다.
     */
    private boolean hasRemarkColumn() {
        Boolean cached = this.remarkColumn;
        if (cached != null) return cached;
        boolean found = false;
        try {
            Map<String, Object> r = this.sqlRunner.getRow("""
                    SELECT 1 AS ok FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'tb_pb209'
                       AND column_name = 'remark'
                    """, new MapSqlParameterSource());
            found = (r != null);
        } catch (Exception e) {
            log.warn("tb_pb209.remark 컬럼 확인에 실패해 조정 이력을 남기지 않습니다.", e);
        }
        if (!found) {
            log.info("tb_pb209.remark 컬럼이 없어 조정 이력을 남기지 않습니다. "
                    + "_backup/20261106_actas_pb209_remark_01_add_column.sql 참고");
        }
        this.remarkColumn = found;
        return found;
    }

    // --------------------------------------------------------------- 계산

    /**
     * PB 근속월수별 연차일수.
     * {@code <=24 15, <=48 16, <=72 17, <=96 18, <=120 19, <=144 20, <=168 21,
     *        <=192 22, <=216 23, <=240 24, ELSE 25}
     *
     * 입력은 실제 경과월수가 아니라 {@code k * 12} 다 ({@link #createAll} 참고).
     * 그렇게 넣으면 가산분이 {@code max(0, floor((months-12)/24))} = {@code floor((k-1)/2)} 가 되어
     * 근로기준법 제60조 ④ ({@code 15 + (k-1)/2}, 상한 25)와 정확히 일치한다.
     */
    private BigDecimal tenureDays(long months) {
        int d;
        if      (months <= 24)  d = 15;
        else if (months <= 48)  d = 16;
        else if (months <= 72)  d = 17;
        else if (months <= 96)  d = 18;
        else if (months <= 120) d = 19;
        else if (months <= 144) d = 20;
        else if (months <= 168) d = 21;
        else if (months <= 192) d = 22;
        else if (months <= 216) d = 23;
        else if (months <= 240) d = 24;
        else                    d = 25;
        return BigDecimal.valueOf(d).setScale(1);
    }

    /** 1년 미만 근로자 월차 — 개근 1개월당 1일, 최대 11일 (근로기준법 제60조 제2항) */
    private BigDecimal monthlyDays(long fullAttendanceMonths) {
        if (fullAttendanceMonths <= 0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(Math.min(fullAttendanceMonths, MONTHLY_MAX_DAYS)).setScale(1);
    }

    /** 'yyyy-MM' / 'yyyyMM' 기준일자 → {년, 월}. 비면 정산년도 12월 */
    private int[] parseYm(String baseYm, int settleYear) {
        String s = baseYm == null ? "" : baseYm.replace("-", "").trim();
        if (s.length() >= 6) {
            try {
                int y = Integer.parseInt(s.substring(0, 4));
                int m = Integer.parseInt(s.substring(4, 6));
                if (m >= 1 && m <= 12) return new int[]{y, m};
            } catch (Exception ignore) { /* fall through */ }
        }
        return new int[]{settleYear, 12};
    }

    // --------------------------------------------------------------- 내부

    /** 2.0 → 2, -2.5 → -2.5 */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static List<Map<String, Object>> nvl(List<Map<String, Object>> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }
}
