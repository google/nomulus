// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.webdriver;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** An immutable class represents a screenshot taken in a visual regression test. */
public class ActualScreenshot {

  private static final String IMAGE_FORMAT = "png";
  private String imageKey;
  private BufferedImage bufferedImage;

  private ActualScreenshot(String imageKey, BufferedImage bufferedImage) {
    this.imageKey = imageKey;
    this.bufferedImage = bufferedImage;
  }

  /** Creates an ActualScreenshot from the given image format and byte array. */
  public static ActualScreenshot create(String imageKey, byte[] imageBytes) {
    checkNotNull(imageKey);
    checkNotNull(imageBytes);
    byte[] imageBytesClone = Arrays.copyOf(imageBytes, imageBytes.length);
    ByteArrayInputStream imageInputStream = new ByteArrayInputStream(imageBytesClone);
    ImageReader imageReader = ImageIO.getImageReadersByFormatName(IMAGE_FORMAT).next();
    try {
      imageReader.setInput(checkNotNull(ImageIO.createImageInputStream(imageInputStream)));
      BufferedImage bufferedImage = checkNotNull(imageReader.read(0));
      return new ActualScreenshot(imageKey, bufferedImage);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@link BufferedImage#getSubimage(int, int, int, int)} */
  ActualScreenshot getSubimage(int x, int y, int w, int h) {
    return new ActualScreenshot(imageKey, bufferedImage.getSubimage(x, y, w, h));
  }

  /** {@link BufferedImage#getWidth()} */
  public int getWidth() {
    return bufferedImage.getWidth();
  }

  /** {@link BufferedImage#getHeight()} */
  public int getHeight() {
    return bufferedImage.getHeight();
  }

  /** {@link BufferedImage#getRGB(int, int)} */
  public int getRGB(int x, int y) {
    return bufferedImage.getRGB(x, y);
  }

  /** Writes the underlying BufferedImage to the given file. */
  public void writeTo(File file) {
    try {
      checkState(ImageIO.write(bufferedImage, IMAGE_FORMAT, checkNotNull(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the concat of imageKey and imageFormat. */
  public String getImageName() {
    return String.join(".", imageKey, IMAGE_FORMAT);
  }
}
