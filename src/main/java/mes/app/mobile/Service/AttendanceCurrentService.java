package mes.app.mobile.Service;

import mes.domain.entity.approval.TB_E080_PK;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AttendanceCurrentService {
    @Autowired
    SqlRunner sqlRunner;

    /**
     * 사용자 연차정보 조회 (올해 = 회계연도 발생분).
     *
     * 예전에는 연도 조건이 없어서 2025·2026 발생행이 둘 다 있으면 DB 가 먼저 내주는 행이
     * 잡혔다 — 모바일에 작년 숫자가 뜰 수 있었다. 지금은 올해 발생행 1행으로 못 박는다.
     *
     * 잔여도 저장된 restnum 이 아니라 사용분을 뺀 값을 다시 계산한다. restnum 은 생성·조정
     * 시점의 스냅샷이라 휴가가 한 건 확정되면 바로 어긋난다. 또 t.daynum 은 이 테이블에서
     * 늘 비어 있어(발생행 전용 구조) 사용일수로 쓸 수 없었다.
     * 사용분은 연차를 차감하는 휴가구분(tb_pb210.yearflag='1')만 센다.
     */
    public Map<String, Object> getAnnInfo(int personId) {

        String year = String.valueOf(java.time.LocalDate.now().getYear());

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personId);
        dicParam.addValue("year", year);
        dicParam.addValue("reqdate", year + "0000");

        /* 발생은 그 해 모든 발생행의 합이다. 월차는 근로기준법 제60조 제2항대로 1개월
           개근마다 1일씩 따로 행이 쌓이므로, 한 행만 집어 오면 입사 1~2년차 사원의
           발생일수가 통째로 빠진다. 이월·조정은 연 단위 행(hflag='0')에만 있다. */
        String sql = """
                SELECT COALESCE(t.ewolnum, 0)  AS ewolnum,
                       COALESCE(t.holinum, 0)  AS holinum,
                       COALESCE(t.monthnum, 0) AS monthnum,
                       COALESCE(u.daynum, 0)   AS daynum,
                       COALESCE(t.ewolnum, 0) + COALESCE(t.holinum, 0)
                           + COALESCE(t.monthnum, 0) - COALESCE(u.daynum, 0) AS restnum,
                       p.rtdate
                  FROM person p
                  JOIN LATERAL (
                        SELECT SUM(COALESCE(x.holinum, 0)) AS holinum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.ewolnum, 0) ELSE 0 END)  AS ewolnum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.monthnum, 0) ELSE 0 END) AS monthnum,
                               MIN(x.spjangcd) AS spjangcd
                          FROM tb_pb209 x
                         WHERE x.personid = p.id
                           AND LEFT(x.reqdate, 4) = :year
                        HAVING COUNT(*) > 0
                  ) t ON TRUE
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(v.daynum, 0)) AS daynum
                          FROM tb_pb204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.spjangcd
                                         AND w.workcd   = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.spjangcd = t.spjangcd
                           AND v.personid = p.id
                           AND COALESCE(v.fixflag, '') = '1'
                           AND v.reqdate >= :year || '0101'
                           AND v.reqdate <= :year || '1231'
                  ) u ON TRUE
                 WHERE p.id = :personid
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }
    // 사용자 휴가정보 조회
    public List<Map<String, Object>> getVacInfo(String workcd, String searchYear, int personId) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personId);
        dicParam.addValue("workcd", workcd);
        dicParam.addValue("searchYear", searchYear);

        String sql = """
                SELECT t.reqdate,
                    t.id,
                    t.workcd,
                    i.worknm,
                    t.yearflag,
                    t.frdate,
                    t.sttime,
                    t.edtime,
                    t.todate,
                    t.daynum,
                    t.remark,
                    t.appgubun,
                    t.fixflag
                FROM tb_pb204 t
                LEFT JOIN tb_pb210 i ON t.workcd = i.workcd 
                WHERE personid = :personid
        		""";
        if(workcd != null && !workcd.isEmpty()){
            dicParam.addValue("workcd", workcd);
            sql += " AND t.workcd = :workcd";
        }
        if (searchYear != null && !searchYear.isEmpty()) {
            dicParam.addValue("searchYear", searchYear);
            sql += " AND LEFT(t.reqdate, 4) = :searchYear";
        }
        sql += " ORDER BY t.reqdate DESC";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 휴가결재데이터 조회(appnum)
    public Map<String, Object> getAppInfo(String appnum) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("appnum", appnum);

        String sql = """
                SELECT *
                FROM tb_e080
                WHERE appnum = :appnum
        		""";

        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }
}
