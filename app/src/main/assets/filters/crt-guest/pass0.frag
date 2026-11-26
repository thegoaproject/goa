#version 300 es
precision mediump float;
precision highp int;

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
};

uniform Push params;

uniform highp sampler2D Source;

layout(location = 0) out highp vec4 FragColor;
in highp vec2 vTexCoord;

void main()
{
    FragColor = vec4(texture(Source, vTexCoord).xyz, 1.0);
}

