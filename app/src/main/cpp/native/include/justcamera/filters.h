#ifndef JUSTCAMERA_NATIVE_FILTERS_H
#define JUSTCAMERA_NATIVE_FILTERS_H

#include "justcamera/image_frame.h"
#include "justcamera/lut.h"

namespace justcamera::processing {

void ProcessExposure(ImageFrameView& frame, float exposure_ev, float strength) noexcept;
void ProcessContrast(ImageFrameView& frame, float contrast, float strength) noexcept;
void ProcessSaturation(ImageFrameView& frame, float saturation, float strength) noexcept;
void ProcessLut3d(ImageFrameView& frame, const Lut3dView& lut, float strength) noexcept;

}  // namespace justcamera::processing

#endif  // JUSTCAMERA_NATIVE_FILTERS_H
