__kernel void opm_runtime_affine_2d_nearest(
    IMAGE_input_TYPE input,
    IMAGE_output_TYPE output,
    IMAGE_mat_TYPE mat)
{
  const sampler_t sampler = CLK_NORMALIZED_COORDS_TRUE | CLK_ADDRESS_CLAMP | CLK_FILTER_NEAREST;
  const int i = get_global_id(0);
  const int j = get_global_id(1);
  const float x = i + 0.5f;
  const float y = j + 0.5f;
  const float x2 = mat[0] * x + mat[1] * y + mat[2];
  const float y2 = mat[3] * x + mat[4] * y + mat[5];
  /* ImageJ's nearest mapper rounds with Java's truncation toward zero. */
  const int tx_flipped = convert_int_rtz(x2);
  const int ty = convert_int_rtz(y2);
  const int tx = GET_IMAGE_WIDTH(input) - 1 - tx_flipped;
  float value = 0;
  if (tx >= 0 && tx < GET_IMAGE_WIDTH(input) && ty >= 0 && ty < GET_IMAGE_HEIGHT(input))
    value = (float)(READ_input_IMAGE(input, sampler, POS_input_INSTANCE(tx, ty, 0, 0)).x);
  WRITE_output_IMAGE(output, POS_output_INSTANCE(i, j, 0, 0), CONVERT_output_PIXEL_TYPE(value));
}
