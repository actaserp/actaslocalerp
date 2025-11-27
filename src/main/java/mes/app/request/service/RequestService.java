package mes.app.request.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RequestService {
    @Autowired
    SqlRunner sqlRunner;
}
