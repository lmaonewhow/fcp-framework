package fcp;
public final class FcpException extends RuntimeException {
    private static final long serialVersionUID=1L;
    public final String code;
    public FcpException(String code,String message){super(message);this.code=code;}
    public FcpException(String code,String message,Throwable cause){super(message,cause);this.code=code;}
}
