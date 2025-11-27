package mes.app.request;

import lombok.extern.slf4j.Slf4j;
import mes.app.request.service.RequestService;
import mes.domain.entity.TbAs011;
import mes.domain.repository.TbAs010Repository;
import mes.domain.repository.TbAs011Repository;
import mes.domain.repository.TbAs020Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/request")
public class RequestController {

    @Autowired
    RequestService requestService;

    @Autowired
    private TbAs011Repository tbAs011Repository;

    @Autowired
    private TbAs010Repository tbAs010Repository;

    @Autowired
    private TbAs020Repository tbAs020Repository;

}
