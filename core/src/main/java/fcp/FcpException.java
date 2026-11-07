package fcp;
public final class FcpException extends RuntimeException {
    private static final long serialVersionUID=1L;
    public final String code;
    /** Trusted runtime retry hint; -1 means no scheduling advice. Never bypasses admission checks. */
    public final long retryAfterMillis;
    public FcpException(String code,String message){super(message);this.code=code;this.retryAfterMillis=-1;}
    public FcpException(String code,String message,Throwable cause){super(message,cause);this.code=code;this.retryAfterMillis=-1;}
    public FcpException(String code,String message,long retryAfterMillis){super(message);this.code=code;this.retryAfterMillis=retryAfterMillis;}
}
