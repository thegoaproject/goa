#version 300 es

struct UBO
{
    highp mat4 MVP;
};

uniform UBO global;

struct Push
{
    highp vec4 LinearizePassSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float SIZEHB;
    highp float SIGMA_HB;
    highp float FINE_BLOOM;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;
float FINE_BLOOM;

void main()
{
    float _23;
    if (params.FINE_BLOOM > 0.5)
    {
        _23 = params.FINE_BLOOM;
    }
    else
    {
        _23 = mix(0.75, 0.5, -params.FINE_BLOOM);
    }
    FINE_BLOOM = _23;
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}

