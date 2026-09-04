#version 150
uniform sampler2D Source;
uniform sampler2D Backdrop;
uniform int BlendMode;
uniform vec4 Viewport;
in vec2 uv;
in vec4 tint;
out vec4 fragColor;
void main() {
    vec4 s=texture(Source,uv)*tint;
    if(s.a<=.00001) discard;
    if(BlendMode!=0) {
        vec4 d=texture(Backdrop,(gl_FragCoord.xy-Viewport.xy)/Viewport.zw);
        vec3 b=s.rgb;
        if(BlendMode==1) b=min(vec3(1.0),d.rgb+s.rgb);
        if(BlendMode==2) b=d.rgb*s.rgb;
        if(BlendMode==3) b=1.0-(1.0-d.rgb)*(1.0-s.rgb);
        if(BlendMode==4) b=max(vec3(0.0),d.rgb-s.rgb);
        if(BlendMode==5) b=max(d.rgb,s.rgb);
        if(BlendMode==6) b=min(d.rgb,s.rgb);
        if(BlendMode==7) b=abs(d.rgb-s.rgb);
        if(BlendMode==8) b=mix(2.0*d.rgb*s.rgb,1.0-2.0*(1.0-d.rgb)*(1.0-s.rgb),step(vec3(.5),d.rgb));
        s.rgb=mix(s.rgb,b,d.a);
    }
    fragColor=s;
}
