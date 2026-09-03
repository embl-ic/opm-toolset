__kernel void opm_runtime_affine_2d_interpolate(
    IMAGE_input_TYPE input,
    IMAGE_output_TYPE output,
    IMAGE_mat_TYPE mat)
{
  const sampler_t sampler = CLK_NORMALIZED_COORDS_TRUE | CLK_ADDRESS_CLAMP | CLK_FILTER_LINEAR;
  const int i = get_global_id(0);
  const int j = get_global_id(1);
  const int nx = GET_IMAGE_WIDTH(input);
  const int ny = GET_IMAGE_HEIGHT(input);
  const float x = i + 0.5f;
  const float y = j + 0.5f;
  const float x2 = mat[0] * x + mat[1] * y + mat[2];
  const float y2 = mat[3] * x + mat[4] * y + mat[5];
  float value = 0;
  /* SIFT/ImageJ uses zero outside integer pixel-centre coordinates [0,n-1]. */
  if (x2 >= 0.5f && x2 <= nx - 0.5f && y2 >= 0.5f && y2 <= ny - 0.5f) {
    const float2 coordinate = (float2)(x2 / nx, y2 / ny);
    value = (float)(READ_input_IMAGE(input, sampler, coordinate).x);
  }
  WRITE_output_IMAGE(output, (int2)(i, j), CONVERT_output_PIXEL_TYPE(value));
}
