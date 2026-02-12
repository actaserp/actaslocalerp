package mes.domain.model;

import java.io.Serializable;

public class AjaxResult implements Serializable {

    private static final long serialVersionUID = 1L;

	public AjaxResult() {
		
	}
	
	public boolean success = true;
	public String message = "";
	public Object data = null;	
	public String code="";
	public String StateName= "";
	
}
