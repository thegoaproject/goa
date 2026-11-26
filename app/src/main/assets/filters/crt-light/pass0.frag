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
    highp float BLURSCALEX;
    highp float LOWLUMSCAN;
    highp float HILUMSCAN;
    highp float BRIGHTBOOST;
    highp float MASK_DARK;
    highp float MASK_FADE;
};

uniform Push params;

uniform highp sampler2D Source;

in highp vec2 vTexCoord;
in highp vec2 invDims;
layout(location = 0) out highp vec4 FragColor;
in highp float maskFade;

void main()
{
    highp vec2 p = vTexCoord * params.SourceSize.xy;
    highp vec2 i = floor(p) + vec2(0.5);
    highp vec2 f = p - i;
    p = (i + (((f * 4.0) * f) * f)) * invDims;
    p.x = mix(p.x, vTexCoord.x, params.BLURSCALEX);
    highp float Y = f.y * f.y;
    highp float YY = Y * Y;
    highp float whichmask = fract(floor(vTexCoord.x * params.OutputSize.x) * (-0.4999000132083892822265625));
    highp float mask = 1.0 + (float(whichmask < 0.5) * (-params.MASK_DARK));
    highp vec3 colour = texture(Source, p).xyz;
    highp float scanLineWeight = params.BRIGHTBOOST - (params.LOWLUMSCAN * (Y - (2.0499999523162841796875 * YY)));
    highp float scanLineWeightB = 1.0 - (params.HILUMSCAN * (YY - ((2.7999999523162841796875 * YY) * Y)));
    highp vec3 _146 = colour * mix(scanLineWeight * mask, scanLineWeightB, dot(colour, vec3(maskFade)));
    FragColor.x = _146.x;
    FragColor.y = _146.y;
    FragColor.z = _146.z;
}

