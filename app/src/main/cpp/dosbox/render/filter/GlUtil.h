#pragma once

// Small GLES 3.0 helpers shared by the filter engine (render thread only).

#include <GLES3/gl3.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOGI_GLU(...) __android_log_print(ANDROID_LOG_INFO, "GoaFilter", __VA_ARGS__)
#define LOGE_GLU(...) __android_log_print(ANDROID_LOG_ERROR, "GoaFilter", __VA_ARGS__)

namespace glutil {

inline GLuint compile(GLenum type, const char* src, std::string& log) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint n = 0;
        glGetShaderiv(s, GL_INFO_LOG_LENGTH, &n);
        std::vector<char> buf(n > 1 ? n : 1);
        glGetShaderInfoLog(s, (GLsizei)buf.size(), nullptr, buf.data());
        log += (type == GL_VERTEX_SHADER ? "vertex: " : "fragment: ") + std::string(buf.data());
        glDeleteShader(s);
        return 0;
    }
    return s;
}

// Compiles and links; attributes Position/TexCoord are bound to 0/1 (slang's layout, and ours)
inline GLuint buildProgram(const char* vertex, const char* fragment, std::string& log) {
    GLuint vs = compile(GL_VERTEX_SHADER, vertex, log);
    if (!vs) return 0;
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragment, log);
    if (!fs) { glDeleteShader(vs); return 0; }
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glBindAttribLocation(p, 0, "Position");
    glBindAttribLocation(p, 1, "TexCoord");
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint n = 0;
        glGetProgramiv(p, GL_INFO_LOG_LENGTH, &n);
        std::vector<char> buf(n > 1 ? n : 1);
        glGetProgramInfoLog(p, (GLsizei)buf.size(), nullptr, buf.data());
        log += "link: " + std::string(buf.data());
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

// A unit quad: Position (x, y, 0, 1) and TexCoord (x, y) over 0..1, drawn as a 4-vertex triangle strip
inline void makeQuad(GLuint& vao, GLuint& vbo) {
    const float v[] = {
        0, 0, 0, 1,  0, 0,
        1, 0, 0, 1,  1, 0,
        0, 1, 0, 1,  0, 1,
        1, 1, 0, 1,  1, 1,
    };
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(v), v, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), (void*)(4 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

// (Re)allocates a texture + framebuffer render target; false if the framebuffer isn't complete
inline bool allocTarget(GLuint& tex, GLuint& fbo, int w, int h, GLenum internalFormat, bool mipmapped = false) {
    if (!tex) glGenTextures(1, &tex);
    if (!fbo) glGenFramebuffers(1, &fbo);
    glBindTexture(GL_TEXTURE_2D, tex);
    GLenum format = GL_RGBA, type = GL_UNSIGNED_BYTE;
    if (internalFormat == GL_RGBA16F) type = GL_HALF_FLOAT;
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mipmapped ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!complete) LOGE_GLU("framebuffer %dx%d format 0x%x incomplete", w, h, internalFormat);
    return complete;
}

inline GLenum wrapMode(const std::string& wrap) {
    if (wrap == "repeat") return GL_REPEAT;
    if (wrap == "mirrored_repeat") return GL_MIRRORED_REPEAT;
    return GL_CLAMP_TO_EDGE;   // clamp_to_border isn't in GLES 3.0
}

inline bool hasExtension(const char* name) {
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; ++i) {
        const char* e = (const char*)glGetStringi(GL_EXTENSIONS, (GLuint)i);
        if (e && std::string(e) == name) return true;
    }
    return false;
}

}  // namespace glutil
