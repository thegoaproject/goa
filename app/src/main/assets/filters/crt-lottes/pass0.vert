#version 300 es

struct UBO
{
    highp mat4 MVP;
    highp vec4 OutputSize;
    highp vec4 OriginalSize;
    highp vec4 SourceSize;
};

uniform UBO global;

struct Push
{
    highp float hardScan;
    highp float hardPix;
    highp float warpX;
    highp float warpY;
    highp float maskDark;
    highp float maskLight;
    highp float scaleInLinearGamma;
    highp float shadowMask;
    highp float brightBoost;
    highp float hardBloomScan;
    highp float hardBloomPix;
    highp float bloomAmount;
    highp float shape;
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

