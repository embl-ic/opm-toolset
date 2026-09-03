__kernel void opm_runtime_affine_3d_nearest(
    IMAGE_input_TYPE input,
    IMAGE_output_TYPE output,
    IMAGE_mat_TYPE mat)
{
  const sampler_t sampler = CLK_NORMALIZED_COORDS_TRUE | CLK_ADDRESS_CLAMP | CLK_FILTER_NEAREST;
  const int i = get_global_id(0);
  const int j = get_global_id(1);
  const int k = get_global_id(2);
  const float x = i + 0.5f;
  const float y = j + 0.5f;
  const float z = k + 0.5f;
  const float x2 = mat[0] * x + mat[1] * y + mat[2] * z + mat[3];
  const float y2 = mat[4] * x + mat[5] * y + mat[6] * z + mat[7];
  const float z2 = mat[8] * x + mat[9] * y + mat[10] * z + mat[11];
  const int tx_flipped = convert_int_rtz(x2);
  const int ty = convert_int_rtz(y2);
  const int tz = convert_int_rtz(z2);
  const int tx = GET_IMAGE_WIDTH(input) - 1 - tx_flipped;
  float value = 0;
  if (tx >= 0 && tx < GET_IMAGE_WIDTH(input) &&
      ty >= 0 && ty < GET_IMAGE_HEIGHT(input) &&
      tz >= 0 && tz < GET_IMAGE_DEPTH(input))
    value = (float)(READ_input_IMAGE(input, sampler, POS_input_INSTANCE(tx, ty, tz, 0)).x);
  WRITE_output_IMAGE(output, POS_output_INSTANCE(i, j, k, 0), CONVERT_output_PIXEL_TYPE(value));
}
