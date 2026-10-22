package com.cheatprogram.hud;

/** Pure, bounded voice animation. Integrating smoothed amplitude avoids phase jumps on loudness changes. */
public final class VoiceLiquidMotion {
    private double phase, level, thinkingTime;
    private float result, resultVelocity;
    private boolean thinking;

    public void reset() { phase=level=thinkingTime=0; result=resultVelocity=0; thinking=false; }
    public void update(double seconds,float amplitude,boolean listening,boolean think,boolean showResult) {
        double dt=Double.isFinite(seconds)?Math.max(0,Math.min(.1,seconds)):0;
        double target=listening&&Float.isFinite(amplitude)?Math.max(0,Math.min(1,amplitude)):0;
        double k=target>level?14:6, change=level-target, e=Math.exp(-k*dt);
        double integral=target*target*dt+2*target*change*(1-e)/k+change*change*(1-e*e)/(2*k);
        phase=Math.IEEEremainder(phase+(listening?.065*dt+5.1*integral:.4*dt),Math.PI*4);
        level=target+change*e;
        if(think&&!thinking)thinkingTime=0;
        thinking=think;if(think)thinkingTime=(thinkingTime+dt)%3.4;
        double remaining=dt;
        while(remaining>1e-7) {
            float step=(float)Math.min(remaining,1.0/240),goal=showResult?1:0;
            resultVelocity+=((goal-result)*210-resultVelocity*21)*step;
            result+=resultVelocity*step;remaining-=step;
        }
        result=Math.max(-.035f,Math.min(1.06f,result));
        if(!Float.isFinite(result)||!Float.isFinite(resultVelocity)){result=showResult?1:0;resultVelocity=0;}
    }
    public float phase(){return (float)phase;}
    public float amplitude(){return (float)level;}
    public float result(){return Math.max(0,Math.min(1,result));}
    public float elasticity(){return result;}
    public float stretch(){return thinking?(float)(1+.48*(.5-.5*Math.cos(thinkingTime*Math.PI*2/1.7))):1;}
}
