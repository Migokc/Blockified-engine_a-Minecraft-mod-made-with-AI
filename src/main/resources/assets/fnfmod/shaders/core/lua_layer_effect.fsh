#version 150
uniform sampler2D Source;
uniform sampler2D Mask;
uniform int HasMask, MaskLuma, MaskInvert, GradientType, StopCount, ClipType;
uniform vec4 MaskTransform, MaskStyle, GradientGeometry, ClipBounds, ClipStyle;
uniform float GradientStrength;
uniform vec4 Stop0;
uniform vec4 Stop1;
uniform vec4 Stop2;
uniform vec4 Stop3;
uniform vec4 Stop4;
uniform vec4 Stop5;
uniform vec4 Stop6;
uniform vec4 Stop7;
uniform vec4 Stop8;
uniform vec4 Stop9;
uniform vec4 Stop10;
uniform vec4 Stop11;
uniform vec4 Stop12;
uniform vec4 Stop13;
uniform vec4 Stop14;
uniform vec4 Stop15;
uniform vec4 Positions0, Positions1, Positions2, Positions3;
in vec2 uv;
out vec4 fragColor;
vec4 gradient(float t) {
    vec4 colors[16] = vec4[16](Stop0, Stop1, Stop2, Stop3, Stop4, Stop5, Stop6, Stop7, Stop8, Stop9, Stop10, Stop11, Stop12, Stop13, Stop14, Stop15);
    float positions[16] = float[16](Positions0.x, Positions0.y, Positions0.z, Positions0.w, Positions1.x, Positions1.y, Positions1.z, Positions1.w, Positions2.x, Positions2.y, Positions2.z, Positions2.w, Positions3.x, Positions3.y, Positions3.z, Positions3.w);
    vec4 c = colors[0];
    for (int i=1; i<16; i++) {
        if (i>=StopCount) break;
        if (t<=positions[i]) return mix(colors[i-1], colors[i], clamp((t-positions[i-1])/max(.00001,positions[i]-positions[i-1]),0.0,1.0));
        c=colors[i];
    }
    return c;
}
float maskAt(vec2 p) {
    if (any(lessThan(p,vec2(0.0))) || any(greaterThan(p,vec2(1.0)))) return 0.0;
    vec4 m=texture(Mask,vec2(p.x,1.0-p.y));
    return m.a * (MaskLuma==1 ? dot(m.rgb,vec3(.2126,.7152,.0722)) : 1.0);
}
void main() {
    vec2 p=vec2(uv.x,1.0-uv.y);
    vec4 c=texture(Source,uv);
    // GuiGraphics produces premultiplied source-over colour in the isolated target.
    c.rgb=c.a>.00001 ? c.rgb/c.a : vec3(0.0);
    if (GradientType!=0) {
        vec2 q=p-GradientGeometry.xy;
        float angle=radians(GradientGeometry.w);
        float t=.5+dot(q,vec2(cos(angle),sin(angle)));
        if (GradientType==2) t=length(q)/max(.00001,abs(GradientGeometry.z));
        if (GradientType==3) t=fract(atan(q.y,q.x)/6.28318530718-angle/6.28318530718+.5);
        c*=mix(vec4(1.0),gradient(clamp(t,0.0,1.0)),clamp(GradientStrength,0.0,1.0));
    }
    if (HasMask!=0) {
        vec2 q=p-.5-MaskTransform.xy;
        float a=radians(-MaskStyle.x);
        q=mat2(cos(a),sin(a),-sin(a),cos(a))*q;
        q=q/max(abs(MaskTransform.zw),vec2(.00001))*sign(MaskTransform.zw)+.5;
        float m=0.0;
        if (HasMask==1) {
            if (MaskStyle.y<=0.0) m=maskAt(q);
            else for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++)
                m+=maskAt(q+vec2(x,y)*MaskStyle.y)/9.0;
        }
        // A missing mask fails closed, even if inversion was requested.
        if (HasMask==1 && MaskInvert==1) m=1.0-m;
        c.a*=m;
    }
    if (ClipType!=0) {
        vec2 halfSize=max(ClipBounds.zw*.5,vec2(.00001));
        vec2 q=p-ClipBounds.xy-halfSize;
        float d;
        if (ClipType==2) d=(length(q/halfSize)-1.0)*min(halfSize.x,halfSize.y);
        else {
            float r=ClipType==3 ? clamp(ClipStyle.x,0.0,min(halfSize.x,halfSize.y)) : 0.0;
            vec2 z=abs(q)-halfSize+r;
            d=length(max(z,0.0))+min(max(z.x,z.y),0.0)-r;
        }
        float soft=max(ClipStyle.y,max(ClipStyle.z,ClipStyle.w)*.5);
        c.a*=1.0-smoothstep(-soft,soft,d);
    }
    fragColor=vec4(clamp(c.rgb,0.0,1.0),clamp(c.a,0.0,1.0));
}
