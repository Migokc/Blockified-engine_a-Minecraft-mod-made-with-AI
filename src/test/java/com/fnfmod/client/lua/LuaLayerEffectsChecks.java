package com.fnfmod.client.lua;

import org.joml.Matrix4f;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.util.*;

/** Executable API/transform regression tests, also used without launching Minecraft. */
public final class LuaLayerEffectsChecks {
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void close(double a,double b,String message) { check(Math.abs(a-b)<.0001,message+": "+a+" != "+b); }
    public static void main(String[] args) throws Exception {
        Map<String,LuaTable> objects=new HashMap<>();List<String> tweens=new ArrayList<>();
        Globals globals=JsePlatform.standardGlobals();
        LuaLayerEffects.install(globals,new LuaLayerEffects.Host(){
            public LuaTable data(String id){return objects.get(id);}
            public LuaTable create(String kind,String id,double x,double y,double w,double h){
                LuaTable d=new LuaTable();d.set("kind",kind);d.set("x",x);d.set("y",y);d.set("width",w);d.set("height",h);objects.put(id,d);return d;
            }
            public void tween(String tag,String id,String field,double to,double seconds,String easing){
                tweens.add(tag+":"+id+":"+field+":"+to+":"+seconds+":"+easing);
            }
        });
        globals.load("""
            makeLuaGradient('fade', 0, 0, 400, 200, 'linear', {
                {at=0, color='black', alpha=1}, {at=.5, color='#FF0000', alpha=.5}, {at=1, color='white', alpha=0}
            }, 90)
            makeLuaGradient('image', 10, 20)
            setObjectMask('image', 'fade', 'alpha')
            setBlendMode('image', 'overlay')
            setObjectClip('image', 'rounded', .1, .2, .8, .6, .05, .01)
            doTweenEffect('turn', 'fade', 'gradientAngle', 180, 2, 'expoOut')
            assert(not pcall(function() setObjectMask('fade','image') end))
            assert(not pcall(function() setObjectMask('image','missing') end))
            assert(not pcall(function() setBlendMode('image','garbage') end))
            assert(not pcall(function() doTweenEffect('x','fade','parent',1) end))
            makeLuaGroup('group')
            addToGroup('group','image')
            assert(not pcall(function() addToGroup('image','group') end))
            removeFromGroup('image')
            """).call();
        var fade=objects.get("fade");var image=objects.get("image");
        var stops=LuaLayerEffects.stops(fade);
        check(stops.size()==3,"multi-stop gradient");check(stops.get(0).color()==0,"black alpha mask remains black");
        close(stops.get(0).alpha(),1,"opaque black reveals in alpha mode");close(stops.get(2).alpha(),0,"transparent stop");
        check(LuaLayerEffects.hidden(fade),"mask-only source excluded from scene");
        check(image.get("maskMode").tojstring().equals("alpha"),"alpha default");
        close(LuaLayerEffects.number(image,"maskScaleX"),1,"mask scale default");
        check(tweens.equals(List.of("turn:fade:gradientAngle:180.0:2.0:expoOut")),"tween bridge");
        close(LuaLayerEffects.number(image,"clipWidth"),.8,"clip bounds");
        fade.set("gradientStops","");fade.set("gradientColor1",0xFF00FF);
        check(LuaLayerEffects.stops(fade).get(0).color()==0xFF00FF,"editor two-stop fallback");
        fade.set("gradientAngle",Double.NaN);close(LuaLayerEffects.number(fade,"gradientAngle"),0,"non-finite property sanitization");
        Map<String,Matrix4f> local=new HashMap<>();local.put("fade",new Matrix4f().translation(50,20,0).rotateZ((float)Math.PI/2));
        local.put("image",new Matrix4f().translation(120,30,0));
        LuaObjectParenting.Host parentHost=new LuaObjectParenting.Host(){
            public LuaTable data(String id){return objects.get(id);}
            public String space(String id){return "screen";}
            public double width(String id){return 100;}public double height(String id){return 100;}
            public Matrix4f transform(String id){
                Matrix4f m=new Matrix4f(local.getOrDefault(id,new Matrix4f()));String p=data(id).get("parent").optjstring("");
                return p.isBlank()?m:transform(p).mul(m);
            }
            public void position(String id,double x,double y,double z){local.put(id,new Matrix4f().translation((float)x,(float)y,(float)z));}
            public void localTransform(String id,Matrix4f m){local.put(id,new Matrix4f(m));}
        };
        LuaObjectParenting.install(globals,parentHost);
        globals.load("setObjectParent('image','fade')").call();
        close(local.get("image").m30(),0,"attach X zero");close(local.get("image").m31(),0,"attach Y zero");
        close(parentHost.transform("image").m30(),50,"child at parent centre");
        parentHost.position("image",10,0,0);
        close(parentHost.transform("image").m31(),30,"local movement follows parent rotation");
        globals.load("assert(not pcall(function() setObjectParent('fade','image') end)); removeObjectParent('image')").call();
        close(parentHost.transform("image").m31(),30,"detach preserves world centre");
        globals.load("setObjectParent('image','fade',true)").call();
        close(parentHost.transform("image").m31(),30,"keep position attachment");
        check(globals.get("getObjectParent").call(LuaValue.valueOf("image")).tojstring().equals("fade"),"parent getter");
        System.out.println("Lua layer API and parenting checks passed.");
        if(Arrays.asList(args).contains("--gpu")) LuaLayerShaderChecks.run();
    }
}
