__kernel void opm_runtime_affine_3d_interpolate(
    IMAGE_input_TYPE input,
    IMAGE_output_TYPE output,
    IMAGE_mat_TYPE mat)
{
  const sampler_t sampler = CLK_NORMALIZED_COORDS_TRUE | CLK_ADDRESS_CLAMP | CLK_FILTER_LINEAR;
  const int i = get_global_id(0);
  const int j = get_global_id(1);
  const int k = get_global_id(2);
  const int nx = GET_IMAGE_WIDTH(input);
  const int ny = GET_IMAGE_HEIGHT(input);
  const int nz = GET_IMAGE_DEPTH(input);
  const float x = i + 0.5f;
  const float y = j + 0.5f;
  const float z = k + 0.5f;
  const float x2 = mat[0] * x + mat[1] * y + mat[2] * z + mat[3];
  const float y2 = mat[4] * x + mat[5] * y + mat[6] * z + mat[7];
  const float z2 = mat[8] * x + mat[9] * y + mat[10] * z + mat[11];
  float value = 0;
  if (x2 >= 0.5f && x2 <= nx - 0.5f &&
      y2 >= 0.5f && y2 <= ny - 0.5f &&
      z2 >= 0.5f && z2 <= nz - 0.5f) {
    const float4 coordinate = (float4)(x2 / nx, y2 / ny, z2 / nz, 0.f);
    value = (float)(READ_input_IMAGE(input, sampler, coordinate).x);
  }
  WRITE_output_IMAGE(output, (int4)(i, j, k, 0), CONVERT_output_PIXEL_TYPE(value));
}
