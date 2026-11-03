package dev.fcp.dual;

import fcp.*;
import java.math.BigDecimal;
import java.util.*;
import static fcp.Schema.*;

/** Actual in-JVM Java purchase state machine. It never delegates its rules to Python. */
public final class PurchaseRules implements Engine.Provider {
    private static void require(boolean condition,String message){if(!condition)throw new FcpException("BUSINESS_RULE",message);}
    private static String text(Map<String,Object> data,String key,int max){String value=str(data.get(key)).strip();require(!value.isEmpty()&&value.codePointCount(0,value.length())<=max,key+" is required and bounded");return value;}
    public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> input){
        String action=str(input.get("action")),actor=str(input.get("actor"));Map<String,Object> data=obj(input,"data"),body=new LinkedHashMap<>(obj(input,"record"));
        switch(action){
            case "create" -> {
                require(data.keySet().equals(Set.of("title","amount")),"Expected title and amount only");
                String amount=str(data.get("amount"));require(amount.matches("(?:0|[1-9][0-9]{0,9})\\.[0-9]{2}"),"Amount must be a positive two-decimal string");
                require(new BigDecimal(amount).signum()>0,"Amount must be positive");
                body=new LinkedHashMap<>(Json.obj("title",text(data,"title",120),"amount",amount,"owner",actor,"status","draft","decision",""));
            }
            case "submit" -> {require(data.isEmpty(),"No extra submission fields");require(actor.equals(body.get("owner")),"Only requester may submit");require("draft".equals(body.get("status")),"Only a draft can be submitted");body.put("status","submitted");}
            case "approve", "reject" -> {
                require(data.keySet().equals(Set.of("comment")),"A decision comment is required");
                require(!actor.equals(body.get("owner")),"Requester cannot approve own purchase");require("submitted".equals(body.get("status")),"Purchase must be submitted");
                body.put("status",action.equals("approve")?"approved":"rejected");body.put("decision",text(data,"comment",500));
            }
            default -> throw new FcpException("OPERATION_MISSING",action);
        }
        return Json.obj("body",body,"engine","java-native");
    }
}
