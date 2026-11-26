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
    highp float IOS;
    highp float h_sharp;
    highp float s_sharp;
    highp float spike;
    highp float ring;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord * 1.000010013580322265625;
}

