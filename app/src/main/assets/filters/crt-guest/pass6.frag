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
    highp float SIZEVB;
    highp float SIGMA_VB;
    highp float FINE_BLOOM;
};

uniform Push params;

uniform highp sampler2D Source;

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
    invsqrsigma = 1.0 / ((2.0 * params.SIGMA_VB) * params.SIGMA_VB);
    highp vec4 SourceSize1 = params.SourceSize;
    SourceSize1.y = params.OriginalSize.yw.x;
    SourceSize1.w = params.OriginalSize.yw.y;
    SourceSize1 *= vec4(FINE_BLOOM, FINE_BLOOM, 1.0 / FINE_BLOOM, 1.0 / FINE_BLOOM);
    highp float f = fract(SourceSize1.y * vTexCoord.y);
    f = 0.5 - f;
    highp vec2 tex = (floor(SourceSize1.xy * vTexCoord) * SourceSize1.zw) + (SourceSize1.zw * 0.5);
    highp vec4 color = vec4(0.0);
    highp vec2 dy = vec2(0.0, SourceSize1.w);
    highp float wsum = 0.0;
    highp float _122 = -params.SIZEVB;
    highp float n = _122;
    do
    {
        highp vec4 pixel = texture(Source, tex + (dy * n));
        highp float param = n + f;
        highp float w = gaussian(param);
        pixel.w *= (pixel.w * pixel.w);
        color += (pixel * w);
        wsum += w;
        n += 1.0;
    } while (n <= params.SIZEVB);
    color /= vec4(wsum);
    FragColor = vec4(color.xyz, pow(color.w, 0.17499999701976776123046875));
}

