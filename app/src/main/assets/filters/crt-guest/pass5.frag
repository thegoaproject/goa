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
    highp vec4 LinearizePassSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float SIZEHB;
    highp float SIGMA_HB;
    highp float FINE_BLOOM;
};

uniform Push params;

uniform highp sampler2D LinearizePass;

in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 FragColor;
highp float FINE_BLOOM;
highp float invsqrsigma;

highp float gaussian(highp float x)
{
    return exp(((-x) * x) * invsqrsigma);
}

void main()
{
    highp float _27;
    if (params.FINE_BLOOM > 0.5)
    {
        _27 = params.FINE_BLOOM;
    }
    else
    {
        _27 = mix(0.75, 0.5, -params.FINE_BLOOM);
    }
    FINE_BLOOM = _27;
    invsqrsigma = 1.0 / ((2.0 * params.SIGMA_HB) * params.SIGMA_HB);
    highp vec4 SourceSize1 = params.OriginalSize * vec4(FINE_BLOOM, FINE_BLOOM, 1.0 / FINE_BLOOM, 1.0 / FINE_BLOOM);
    highp float f = fract(SourceSize1.x * vTexCoord.x);
    f = 0.5 - f;
    highp vec2 tex = (floor(SourceSize1.xy * vTexCoord) * SourceSize1.zw) + (SourceSize1.zw * 0.5);
    highp vec4 color = vec4(0.0);
    highp vec2 dx = vec2(SourceSize1.z, 0.0);
    highp float wsum = 0.0;
    highp float _113 = -params.SIZEHB;
    highp float n = _113;
    do
    {
        highp vec4 pixel = texture(LinearizePass, tex + (dx * n));
        highp float param = n + f;
        highp float w = gaussian(param);
        pixel.w = max(max(pixel.x, pixel.y), pixel.z);
        pixel.w *= (pixel.w * pixel.w);
        color += (pixel * w);
        wsum += w;
        n += 1.0;
    } while (n <= params.SIZEHB);
    color /= vec4(wsum);
    FragColor = vec4(color.xyz, pow(color.w, 0.3333329856395721435546875));
}

