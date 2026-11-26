#version 300 es
precision mediump float;
precision highp int;

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
    highp float BRIGHT_BOOST;
    highp float DILATION;
    highp float GAMMA_INPUT;
    highp float GAMMA_OUTPUT;
    highp float MASK_SIZE;
    highp float MASK_STAGGER;
    highp float MASK_STRENGTH;
    highp float MASK_DOT_HEIGHT;
    highp float MASK_DOT_WIDTH;
    highp float SCANLINE_CUTOFF;
    highp float SCANLINE_BEAM_WIDTH_MAX;
    highp float SCANLINE_BEAM_WIDTH_MIN;
    highp float SCANLINE_BRIGHT_MAX;
    highp float SCANLINE_BRIGHT_MIN;
    highp float SCANLINE_STRENGTH;
    highp float SHARPNESS_H;
    highp float SHARPNESS_V;
};

uniform Push params;

uniform highp sampler2D Source;

in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 FragColor;

highp float curve_distance(highp float x, highp float sharp)
{
    highp float x_step = step(0.5, x);
    highp float curve = 0.5 - (sqrt(0.25 - ((x - x_step) * (x - x_step))) * sign(0.5 - x));
    return mix(x, curve, sharp);
}

highp vec4 dilate(highp vec4 col)
{
    highp vec4 x = mix(vec4(1.0), col, vec4(params.DILATION));
    return col * x;
}

highp mat4 get_color_matrix(highp vec2 co, highp vec2 dx)
{
    highp vec4 param = texture(Source, co - dx);
    highp vec4 param_1 = texture(Source, co);
    highp vec4 param_2 = texture(Source, co + dx);
    highp vec4 param_3 = texture(Source, co + (dx * 2.0));
    return mat4(vec4(dilate(param)), vec4(dilate(param_1)), vec4(dilate(param_2)), vec4(dilate(param_3)));
}

highp vec3 filter_lanczos(highp vec4 coeffs, highp mat4 color_matrix)
{
    highp vec4 col = color_matrix * coeffs;
    highp vec4 sample_min = min(color_matrix[1], color_matrix[2]);
    highp vec4 sample_max = max(color_matrix[1], color_matrix[2]);
    col = clamp(col, sample_min, sample_max);
    return col.xyz;
}

void main()
{
    highp vec2 dx = vec2(global.SourceSize.z, 0.0);
    highp vec2 dy = vec2(0.0, global.SourceSize.w);
    highp vec2 pix_co = (vTexCoord * global.SourceSize.xy) - vec2(0.5);
    highp vec2 tex_co = (floor(pix_co) + vec2(0.5)) * global.SourceSize.zw;
    highp vec2 dist = fract(pix_co);
    highp float param = dist.x;
    highp float param_1 = params.SHARPNESS_H * params.SHARPNESS_H;
    highp float curve_x = curve_distance(param, param_1);
    highp vec4 coeffs = vec4(1.0 + curve_x, curve_x, 1.0 - curve_x, 2.0 - curve_x) * 3.1415927410125732421875;
    coeffs = max(abs(coeffs), vec4(9.9999997473787516355514526367188e-06));
    coeffs = ((sin(coeffs) * 2.0) * sin(coeffs * 0.5)) / (coeffs * coeffs);
    coeffs /= vec4(dot(coeffs, vec4(1.0)));
    highp vec2 param_2 = tex_co;
    highp vec2 param_3 = dx;
    highp vec4 param_4 = coeffs;
    highp mat4 param_5 = get_color_matrix(param_2, param_3);
    highp vec3 col = filter_lanczos(param_4, param_5);
    highp vec2 param_6 = tex_co + dy;
    highp vec2 param_7 = dx;
    highp vec4 param_8 = coeffs;
    highp mat4 param_9 = get_color_matrix(param_6, param_7);
    highp vec3 col2 = filter_lanczos(param_8, param_9);
    highp float param_10 = dist.y;
    highp float param_11 = params.SHARPNESS_V;
    col = mix(col, col2, vec3(curve_distance(param_10, param_11)));
    col = pow(col, vec3(params.GAMMA_INPUT / (params.DILATION + 1.0)));
    highp float luma = dot(vec3(0.2125999927520751953125, 0.715200006961822509765625, 0.072200000286102294921875), col);
    highp float bright = (max(col.x, max(col.y, col.z)) + luma) * 0.5;
    highp float scan_bright = clamp(bright, params.SCANLINE_BRIGHT_MIN, params.SCANLINE_BRIGHT_MAX);
    highp float scan_beam = clamp(bright * params.SCANLINE_BEAM_WIDTH_MAX, params.SCANLINE_BEAM_WIDTH_MIN, params.SCANLINE_BEAM_WIDTH_MAX);
    highp float scan_weight = 1.0 - (pow((cos(((vTexCoord.y * 2.0) * 3.1415927410125732421875) * global.SourceSize.y) * 0.5) + 0.5, scan_beam) * params.SCANLINE_STRENGTH);
    highp float mask = 1.0 - params.MASK_STRENGTH;
    highp vec2 mod_fac = floor(((vTexCoord * global.OutputSize.xy) * global.SourceSize.xy) / (global.SourceSize.xy * vec2(params.MASK_SIZE, params.MASK_DOT_HEIGHT * params.MASK_SIZE)));
    int dot_no = int(mod((mod_fac.x + (mod(mod_fac.y, 2.0) * params.MASK_STAGGER)) / params.MASK_DOT_WIDTH, 3.0));
    highp vec3 mask_weight;
    if (dot_no == 0)
    {
        mask_weight = vec3(1.0, mask, mask);
    }
    else
    {
        if (dot_no == 1)
        {
            mask_weight = vec3(mask, 1.0, mask);
        }
        else
        {
            mask_weight = vec3(mask, mask, 1.0);
        }
    }
    if (global.SourceSize.y >= params.SCANLINE_CUTOFF)
    {
        scan_weight = 1.0;
    }
    col2 = col;
    col *= vec3(scan_weight);
    col = mix(col, col2, vec3(scan_bright));
    col *= mask_weight;
    col = pow(col, vec3(1.0 / params.GAMMA_OUTPUT));
    FragColor = vec4(col * params.BRIGHT_BOOST, 1.0);
}

