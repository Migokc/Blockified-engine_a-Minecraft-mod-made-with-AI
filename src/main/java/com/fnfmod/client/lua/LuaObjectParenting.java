package com.fnfmod.client.lua;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.*;

/** Transform-only hierarchy: children remain independent layers, not flattened group pixels. */
public final class LuaObjectParenting {
    private LuaObjectParenting() {}
    public interface Host {
        LuaTable data(String id);
        String space(String id);
        Matrix4f transform(String id);
        void position(String id, double x, double y, double z);
        void localTransform(String id, Matrix4f matrix);
        double width(String id);
        double height(String id);
    }
    public static void attach(Host host,String child,String parent,boolean keepPosition) {
        LuaTable c=host.data(child), p=host.data(parent);
        if(c==null || p==null) throw new LuaError("Parent and child must exist");
        if(!host.space(child).equals(host.space(parent))) throw new LuaError("Parent and child must use the same camera/space");
        Set<String> visited=new HashSet<>(); visited.add(child);
        for(String id=parent;!id.isBlank();) {
            if(!visited.add(id)) throw new LuaError("Cyclic object parenting");
            LuaTable item=host.data(id); id=item==null ? "" : item.get("parent").optjstring("");
        }
        Matrix4f old=keepPosition ? host.transform(child) : null;
        Matrix4f basis=host.transform(parent);
        c.set("parent",parent);
        c.set("parentWidth",Math.max(.0001,host.width(parent)));
        c.set("parentHeight",Math.max(.0001,host.height(parent)));
        if(keepPosition) host.localTransform(child,new Matrix4f(basis).invert().mul(old));
        else host.position(child,0,0,0);
    }
    public static void detach(Host host,String child,boolean keepPosition) {
        LuaTable c=host.data(child); if(c==null) throw new LuaError("Unknown child: "+child);
        Matrix4f transform=keepPosition ? host.transform(child) : null;
        c.set("parent",LuaValue.NIL); c.set("parentWidth",LuaValue.NIL); c.set("parentHeight",LuaValue.NIL);
        if(keepPosition) host.localTransform(child,transform);
    }
    public static void install(Globals globals,Host host) {
        globals.set("setObjectParent",new VarArgFunction(){
            @Override public Varargs invoke(Varargs a) {
                attach(host,a.checkjstring(1),a.checkjstring(2),a.optboolean(3,false)); return LuaValue.TRUE;
            }
        });
        globals.set("removeObjectParent",new VarArgFunction(){
            @Override public Varargs invoke(Varargs a) {
                detach(host,a.checkjstring(1),a.optboolean(2,true)); return LuaValue.TRUE;
            }
        });
        globals.set("getObjectParent",new VarArgFunction(){
            @Override public Varargs invoke(Varargs a) {
                LuaTable data=host.data(a.checkjstring(1)); return data==null ? LuaValue.NIL : data.get("parent").optvalue(LuaValue.valueOf(""));
            }
        });
    }
    public static Vector3f rotation(Matrix4f matrix) {
        return matrix.getUnnormalizedRotation(new org.joml.Quaternionf()).normalize().getEulerAnglesXYZ(new Vector3f()).mul((float)(180/Math.PI));
    }
}
