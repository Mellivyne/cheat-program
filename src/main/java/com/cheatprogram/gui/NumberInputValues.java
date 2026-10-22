package com.cheatprogram.gui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;

/** Locale-independent numeric entry shared by settings and callback-backed editor controls. */
public final class NumberInputValues {
    private static final Pattern DECIMAL=Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    public record Result(boolean valid,double value,String problem) { }
    private NumberInputValues(){ }
    public static Result parse(String text,double min,double max,double step){
        if(text==null||!Double.isFinite(min)||!Double.isFinite(max)||!Double.isFinite(step)||min>max||step<0)return invalid("ui.number.invalid");
        String input=text.strip();if(input.isEmpty()||input.length()>128||!DECIMAL.matcher(input).matches())return invalid("ui.number.invalid");
        try{
            BigDecimal entered=new BigDecimal(input);double value=entered.doubleValue();if(!Double.isFinite(value))return invalid("ui.number.invalid");
            BigDecimal low=BigDecimal.valueOf(min),high=BigDecimal.valueOf(max);
            if(entered.compareTo(low)<0||entered.compareTo(high)>0)return invalid("ui.number.outOfRange");
            if(step>0){BigDecimal increment=BigDecimal.valueOf(step),ticks=entered.subtract(low).divide(increment,0,RoundingMode.HALF_UP);
                BigDecimal snapped=low.add(ticks.multiply(increment)).max(low).min(high);value=snapped.setScale(6,RoundingMode.HALF_UP).doubleValue();}
            value=Math.max(min,Math.min(max,value));return new Result(true,value==0?0:value,"");
        }catch(ArithmeticException|NumberFormatException malformed){return invalid("ui.number.invalid");}
    }
    /** Confirmation writes through the existing setting/callback once; invalid drafts do not write. */
    public static Result apply(String text,double min,double max,double step,DoubleConsumer setter){
        Result result=parse(text,min,max,step);if(result.valid())setter.accept(result.value());return result;
    }
    /** Editing text contains the full stored value, without display rounding or a unit suffix. */
    public static String format(double value){
        if(!Double.isFinite(value))return "";BigDecimal number=BigDecimal.valueOf(value==0?0:value).stripTrailingZeros();String plain=number.toPlainString();return plain.length()<=96?plain:number.toString();
    }
    private static Result invalid(String problem){return new Result(false,0,problem);}
}
