#version 300 es

struct UBO
{
    highp mat4 MVP;
};

uniform UBO global;

struct Push
{
    highp vec4 SourceSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float TNTC;
    highp float LS;
    highp float LUTLOW;
    highp float LUTBR;
    highp float CP;
    highp float CS;
    highp float BP;
    highp float WP;
    highp float wp_saturation;
    highp float vigstr;
    highp float vigdef;
    highp float sega_fix;
    highp float pre_bb;
    highp float contr;
    highp float pre_gc;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}

