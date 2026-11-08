package fcp.release.observation;

import fcp.release.routing.DurableReleaseRouter;
import java.io.IOException;
import java.util.Objects;

/** Host wrapper for stdio/plugin invocations. The classifier is host-owned, never a plugin-supplied field. */
public final class ObservedInvocation {
    @FunctionalInterface public interface Call<T>{T invoke()throws IOException;}
    @FunctionalInterface public interface Classifier<T>{RequestObserver.BusinessOutcome classify(T result);}
    private ObservedInvocation(){}
    public static <T> T invoke(RequestObserver observer,DurableReleaseRouter.Decision decision,boolean mayWrite,Call<T> call,Classifier<T> classifier)throws IOException {
        Objects.requireNonNull(observer);Objects.requireNonNull(decision);Objects.requireNonNull(call);
        RequestObserver.Ticket ticket=observer.begin(decision.releaseId(),decision.revision());long started=System.nanoTime();
        boolean technical=true,unknown=false;RequestObserver.BusinessOutcome business=RequestObserver.BusinessOutcome.UNKNOWN;
        try{T result=call.invoke();technical=false;if(classifier!=null){RequestObserver.BusinessOutcome classified=classifier.classify(result);if(classified!=null)business=classified;}return result;}
        catch(IOException|RuntimeException failure){unknown=mayWrite;throw failure;}
        finally{ticket.complete(technical,business,unknown,System.nanoTime()-started);}
    }
}
