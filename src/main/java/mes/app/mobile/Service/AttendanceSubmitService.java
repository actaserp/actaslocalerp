package mes.app.mobile.Service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AttendanceSubmitService {
    @Autowired
    SqlRunner sqlRunner;

    /**
     * 사용자 정보 조회 — 휴가신청 화면 상단의 잔여연차에 쓰인다.
     *
     * 예전에는 발생행을 personid 만으로 붙이고 {@code ORDER BY an.todate DESC} 로 한 행을
     * 골랐다. tb_pb209 의 todate 는 이 구조에서 늘 비어 있어 정렬이 사실상 무작위였고,
     * 2025·2026 발생행이 둘 다 있으면 작년 잔여가 뜰 수 있었다.
     * 지금은 올해(회계연도) 발생행으로 못 박고, 잔여는 사용분을 뺀 값을 다시 계산한다
     * (저장된 restnum 은 생성·조정 시점 스냅샷이라 휴가가 확정되면 어긋난다).
     */
    public Map<String, Object> getUserInfo(String username) {

        String year = String.valueOf(java.time.LocalDate.now().getYear());

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("username", username);
        dicParam.addValue("year", year);
        dicParam.addValue("reqdate", year + "0000");

        String sql = """
                SELECT
                      a.username,
                      a.first_name,
                      p.id,
                      CASE WHEN COALESCE(an.rowcnt, 0) = 0 THEN NULL
                           ELSE COALESCE(an.ewolnum, 0) + COALESCE(an.holinum, 0)
                              + COALESCE(an.monthnum, 0) - COALESCE(u.daynum, 0)
                      END AS restnum,
                      t.sttime
                  FROM auth_user a
                  LEFT JOIN person p ON p.id = a.personid
                  /* 발생은 그 해 모든 발생행의 합. 월차는 1개월 개근마다 1일씩 행이
                     따로 쌓이므로(제60조 제2항) 한 행만 보면 발생일수가 빠진다.
                     이월·조정은 연 단위 행(hflag='0')에만 있다. */
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(x.holinum, 0)) AS holinum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.ewolnum, 0) ELSE 0 END)  AS ewolnum,
                               SUM(CASE WHEN COALESCE(x.hflag, '0') = '0'
                                        THEN COALESCE(x.monthnum, 0) ELSE 0 END) AS monthnum,
                               MIN(x.spjangcd) AS spjangcd,
                               COUNT(*) AS rowcnt
                          FROM tb_pb209 x
                         WHERE x.personid = a.personid
                           AND LEFT(x.reqdate, 4) = :year
                  ) an ON TRUE
                  LEFT JOIN LATERAL (
                        SELECT SUM(COALESCE(v.daynum, 0)) AS daynum
                          FROM tb_pb204 v
                          JOIN tb_pb210 w ON w.spjangcd = v.spjangcd
                                         AND w.workcd   = v.workcd
                                         AND w.yearflag = '1'
                         WHERE v.spjangcd = an.spjangcd
                           AND v.personid = a.personid
                           AND COALESCE(v.fixflag, '') = '1'
                           AND v.reqdate >= :year || '0101'
                           AND v.reqdate <= :year || '1231'
                  ) u ON TRUE
                  LEFT JOIN tb_pbcont t ON t.flag = LPAD(p."PersonGroup_id"::text, 2, '0')
                  WHERE a.username = :username
                  LIMIT 1
        		""";


        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }

    // 결재구분별 결재라인 및 정보 조회(결재자 직원코드)
    public List<Map<String, Object>> getAppInfoList (int personid) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("personid", personid);

        String sql = """
                SELECT
                      *
                  FROM tb_e064 e
                  WHERE e.papercd = '301'
                  AND e.personid = :personid
                  ORDER BY e.SEQ ASC
        		""";


        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 휴가항목 선택시 근태설정 고정값있는지 확인
    public Map<String, Object> getPeriod (String attKind) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("attKind", attKind);

        String sql = """
                SELECT
                      yearflag,
                      usenum
                  FROM tb_pb210
                  WHERE workcd = :attKind
        		""";


        Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

        return item;
    }

}
