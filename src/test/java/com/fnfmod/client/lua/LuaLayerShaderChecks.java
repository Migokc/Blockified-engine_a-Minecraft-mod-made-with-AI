package com.fnfmod.client.lua;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Real driver compilation and pixel checks in a hidden, temporary OpenGL window. */
final class LuaLayerShaderChecks {
    private static final Path ROOT=Path.of("src/main/resources/assets/fnfmod/shaders/core");
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run() throws Exception {
        check(GLFW.glfwInit(),"GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(32,32,"Lua layer shader checks",0,0);
        check(window!=0,"hidden OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            int effect=program("lua_layer_effect"),draw=program("lua_layer_draw");
            int vao=glGenVertexArrays();glBindVertexArray(vao);
            int vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);
            float[] vertices={0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0};
            glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);
            int position=glGetAttribLocation(effect,"Position");glVertexAttribPointer(position,3,GL_FLOAT,false,12,0);glEnableVertexAttribArray(position);
            int source=texture(255,255,255,255),mask=texture(0,0,0,255),output=glGenTextures();
            glBindTexture(GL_TEXTURE_2D,output);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,32,32,0,GL_RGBA,GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"test framebuffer");
            glViewport(0,0,32,32);glDisable(GL_BLEND);glUseProgram(effect);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source);integer(effect,"Source",0);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,mask);integer(effect,"Mask",1);
            vector(effect,"MaskTransform",0,0,1,1);vector(effect,"ClipStyle",.1f,0,1f/32,1f/32);
            integer(effect,"HasMask",1);integer(effect,"MaskLuma",0);
            render();check(pixel(16,16,3)==255,"opaque black alpha mask must reveal");
            integer(effect,"MaskLuma",1);render();check(pixel(16,16,3)==0,"black luminance mask must hide");
            integer(effect,"MaskInvert",1);render();check(pixel(16,16,3)==255,"inverted luminance mask");
            integer(effect,"HasMask",2);render();check(pixel(16,16,3)==0,"missing inverted mask fails closed");
            integer(effect,"HasMask",0);integer(effect,"MaskInvert",0);integer(effect,"GradientType",1);
            vector(effect,"GradientGeometry",.5f,.5f,.5f,0);scalar(effect,"GradientStrength",1);integer(effect,"StopCount",2);
            vector(effect,"Positions0",0,1,1,1);vector(effect,"Stop0",1,0,0,1);vector(effect,"Stop1",0,0,1,0);
            render();check(pixel(2,16,0)>220,"gradient starts red");check(pixel(29,16,2)>220,"gradient ends blue");
            check(pixel(2,16,3)>220&&pixel(29,16,3)<35,"gradient alpha fade");
            integer(effect,"GradientType",0);integer(effect,"ClipType",2);vector(effect,"ClipBounds",0,0,1,1);
            render();check(pixel(0,0,3)==0&&pixel(16,16,3)==255,"circle clipping");
            check(glGetError()==GL_NO_ERROR,"OpenGL errors");
            glDeleteProgram(effect);glDeleteProgram(draw);glDeleteBuffers(vbo);glDeleteVertexArrays(vao);
            glDeleteTextures(source);glDeleteTextures(mask);glDeleteTextures(output);glDeleteFramebuffers(fbo);
            System.out.println("GPU shader compilation and alpha/luminance/gradient/clip pixel checks passed: "+glGetString(GL_RENDERER));
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    private static int program(String name) throws Exception {
        int vertex=shader(GL_VERTEX_SHADER,Files.readString(ROOT.resolve(name+".vsh")));
        int fragment=shader(GL_FRAGMENT_SHADER,Files.readString(ROOT.resolve(name+".fsh")));
        int program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
        check(glGetProgrami(program,GL_LINK_STATUS)!=0,name+": "+glGetProgramInfoLog(program));
        glDeleteShader(vertex);glDeleteShader(fragment);return program;
    }
    private static int shader(int type,String source) {
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        check(glGetShaderi(shader,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(shader));return shader;
    }
    private static int texture(int r,int g,int b,int a) {
        int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);
        var pixel=BufferUtils.createByteBuffer(4).put((byte)r).put((byte)g).put((byte)b).put((byte)a).flip();
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);return texture;
    }
    private static void integer(int p,String n,int v){glUniform1i(glGetUniformLocation(p,n),v);}
    private static void scalar(int p,String n,float v){glUniform1f(glGetUniformLocation(p,n),v);}
    private static void vector(int p,String n,float x,float y,float z,float w){glUniform4f(glGetUniformLocation(p,n),x,y,z,w);}
    private static void render(){glDrawArrays(GL_TRIANGLES,0,6);}
    private static int pixel(int x,int y,int channel){var p=BufferUtils.createByteBuffer(4);glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,p);return p.get(channel)&255;}
}
