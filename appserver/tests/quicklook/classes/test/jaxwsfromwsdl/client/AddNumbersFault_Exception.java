
package jaxwsfromwsdl.client;

import javax.xml.ws.WebFault;


/**
 * This class was generated by the JAX-WS RI.
 * JAX-WS RI 2.3.0
 * Generated source version: 2.2
 * 
 */
@WebFault(name = "AddNumbersFault", targetNamespace = "http://example.org")
public class AddNumbersFault_Exception
    extends Exception
{

    /**
     * Java type that goes as soapenv:Fault detail element.
     * 
     */
    private AddNumbersFault faultInfo;

    /**
     * 
     * @param faultInfo
     * @param message
     */
    public AddNumbersFault_Exception(String message, AddNumbersFault faultInfo) {
        super(message);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @param faultInfo
     * @param cause
     * @param message
     */
    public AddNumbersFault_Exception(String message, AddNumbersFault faultInfo, Throwable cause) {
        super(message, cause);
        this.faultInfo = faultInfo;
    }

    /**
     * 
     * @return
     *     returns fault bean: jaxwsfromwsdl.client.AddNumbersFault
     */
    public AddNumbersFault getFaultInfo() {
        return faultInfo;
    }

}
