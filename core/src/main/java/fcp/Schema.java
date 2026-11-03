package fcp;
import java.util.*;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Explicit schema subset shared with the Python implementation. */
public final class Schema {
    private Schema(){}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){if(!(value instanceof Map))throw new FcpException("INVALID_INPUT","Expected object");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") public static List<Object> list(Object value){if(!(value instanceof List))throw new FcpException("INVALID_INPUT","Expected array");return (List<Object>)value;}
    public static String str(Object value){if(!(value instanceof String))throw new FcpException("INVALID_INPUT","Expected string");return (String)value;}
    public static Map<String,Object> obj(Map<String,Object> value,String key){return map(value.getOrDefault(key,Map.of()));}
    public static List<Object> arr(Map<String,Object> value,String key){return list(value.getOrDefault(key,List.of()));}
    public static long number(Object value){try{return new BigDecimal(value.toString()).longValueExact();}catch(Exception error){throw new FcpException("INVALID_INPUT","Expected integer");}}
    public static Object cloneJson(Object value){return Json.copy(value);}
    public static void ident(Object value){if(!(value instanceof String s)||!s.matches("[a-z][a-z0-9_.-]{0,99}"))throw new FcpException("MANIFEST","Invalid identifier");}
    private static void invalid(String path,String reason){throw new FcpException("INVALID_INPUT",path+": "+reason);}
    private static BigDecimal decimal(Object value){
        try { return new BigDecimal(value.toString()); }
        catch(RuntimeException error){throw new FcpException("INVALID_INPUT","Expected finite number",error);}
    }
    private static boolean equivalent(Object a,Object b) {
        if(a instanceof Number && b instanceof Number)return decimal(a).compareTo(decimal(b))==0;
        if(a instanceof Map<?,?> am && b instanceof Map<?,?> bm) {
            if(!am.keySet().equals(bm.keySet()))return false;
            for(Object k:am.keySet())if(!equivalent(am.get(k),bm.get(k)))return false;
            return true;
        }
        if(a instanceof List<?> al && b instanceof List<?> bl) {
            if(al.size()!=bl.size())return false;
            for(int i=0;i<al.size();i++)if(!equivalent(al.get(i),bl.get(i)))return false;
            return true;
        }
        return Objects.equals(a,b);
    }
    public static void check(Map<String,Object> schema){check(schema,0);}
    private static void check(Map<String,Object> schema,int depth){
        if(depth>24)throw new FcpException("SCHEMA","Schema depth");
        Set<String> allowed=Set.of("type","properties","required","additionalProperties","items","minItems","maxItems","minLength","maxLength","pattern","minimum","maximum","enum","description","title","default");
        for(String key:schema.keySet())if(!allowed.contains(key))throw new FcpException("SCHEMA","Unknown keyword: "+key);
        Object type=schema.get("type");if(type!=null&&!Set.of("object","array","string","integer","number","boolean","null").contains(type))throw new FcpException("SCHEMA","Unknown type");
        for(String key:List.of("minItems","maxItems","minLength","maxLength"))if(schema.containsKey(key)){
            Object v=schema.get(key);
            if(!(v instanceof Number)||decimal(v).signum()<0||decimal(v).stripTrailingZeros().scale()>0)
                throw new FcpException("SCHEMA",key+" must be a nonnegative JSON integer");
        }
        for(String key:List.of("minimum","maximum"))if(schema.containsKey(key)&&!(schema.get(key) instanceof Number))
            throw new FcpException("SCHEMA",key+" must be a JSON number");
        for(String[] bounds:List.of(new String[]{"minimum","maximum"},new String[]{"minItems","maxItems"},new String[]{"minLength","maxLength"}))
            if(schema.containsKey(bounds[0])&&schema.containsKey(bounds[1])&&decimal(schema.get(bounds[0])).compareTo(decimal(schema.get(bounds[1])))>0)
                throw new FcpException("SCHEMA","Inverted bounds");
        if(schema.containsKey("additionalProperties")&&!(schema.get("additionalProperties") instanceof Boolean)&&!(schema.get("additionalProperties") instanceof Map))
            throw new FcpException("SCHEMA","additionalProperties must be a boolean or schema");
        if(schema.containsKey("enum")) {
            List<Object> values=list(schema.get("enum"));
            if(values.isEmpty())throw new FcpException("SCHEMA","Empty enum");
            for(int i=0;i<values.size();i++)for(int j=0;j<i;j++)if(equivalent(values.get(i),values.get(j)))throw new FcpException("SCHEMA","Duplicate enum value");
        }
        Map<String,Object> props=obj(schema,"properties");for(Object child:props.values())check(map(child),depth+1);
        for(Object required:arr(schema,"required"))if(!props.containsKey(required))throw new FcpException("SCHEMA","Unknown required field");
        if(schema.containsKey("items"))check(map(schema.get("items")),depth+1);
        if(schema.get("additionalProperties") instanceof Map)check(map(schema.get("additionalProperties")),depth+1);
        if(schema.containsKey("pattern")){String p=str(schema.get("pattern"));if(p.length()>200||p.contains("(?")||p.contains("\\1")||p.contains("\\2"))throw new FcpException("SCHEMA","Unsupported pattern");Pattern.compile(p);}
    }
    public static void validate(Object value,Map<String,Object> schema){validate(value,schema,"$",0);}
    private static void validate(Object value,Map<String,Object> schema,String path,int depth){
        if(depth>32)invalid(path,"Depth");String type=(String)schema.get("type");
        boolean valid=type==null||switch(type){case "object"->value instanceof Map;case "array"->value instanceof List;case "string"->value instanceof String;case "boolean"->value instanceof Boolean;case "null"->value==null;case "integer"->value instanceof Number&&decimal(value).stripTrailingZeros().scale()<=0;case "number"->value instanceof Number;default->false;};
        if(!valid)invalid(path,"Expected "+type);
        if(schema.containsKey("enum")&&list(schema.get("enum")).stream().noneMatch(item->equivalent(item,value)))invalid(path,"Outside enum");
        if(value instanceof Map){Map<String,Object> object=map(value),props=obj(schema,"properties");for(Object key:arr(schema,"required"))if(!object.containsKey(key))invalid(path,"Required "+key);
            for(var entry:object.entrySet()){if(props.containsKey(entry.getKey()))validate(entry.getValue(),map(props.get(entry.getKey())),path+"."+entry.getKey(),depth+1);else if(Boolean.FALSE.equals(schema.get("additionalProperties")))invalid(path,"Unknown "+entry.getKey());else if(schema.get("additionalProperties") instanceof Map)validate(entry.getValue(),map(schema.get("additionalProperties")),path+"."+entry.getKey(),depth+1);}}
        if(value instanceof List<?> array){if(array.size()<number(schema.getOrDefault("minItems",0))||array.size()>number(schema.getOrDefault("maxItems",10000)))invalid(path,"Array length");for(Object item:array)validate(item,obj(schema,"items"),path+"[]",depth+1);}
        if(value instanceof String text){int length=text.codePointCount(0,text.length());if(length<number(schema.getOrDefault("minLength",0))||length>number(schema.getOrDefault("maxLength",1048576)))invalid(path,"String length");if(schema.containsKey("pattern")&&!Pattern.compile(str(schema.get("pattern"))).matcher(text).find())invalid(path,"Pattern");}
        if(value instanceof Number){if(schema.containsKey("minimum")&&decimal(value).compareTo(decimal(schema.get("minimum")))<0)invalid(path,"Minimum");if(schema.containsKey("maximum")&&decimal(value).compareTo(decimal(schema.get("maximum")))>0)invalid(path,"Maximum");}
    }
}
