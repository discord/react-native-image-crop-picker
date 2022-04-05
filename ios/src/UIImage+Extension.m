//
//  UIImage+Extension.m
//  DoubleConversion
//
//  Created by Ivan Pusic on 09/05/2020.
//

#import "UIImage+Extension.h"
#import <MobileCoreServices/MobileCoreServices.h>
#import <TOCropViewController/UIImage+Animated.h>
#import <TOCropViewController/TOImageFrame.h>

@implementation UIImage (fixOrientation)

- (UIImage *)fixOrientation
{
    // No-op if the orientation is already correct.
    if (self.imageOrientation == UIImageOrientationUp) {
        return self;
    }
    
    // We need to calculate the proper transformation to make the image upright.
    // We do it in 2 steps: Rotate if Left/Right/Down, and then flip if Mirrored.
    CGAffineTransform transform = CGAffineTransformIdentity;
    
    switch (self.imageOrientation) {
        case UIImageOrientationDown:
        case UIImageOrientationDownMirrored:
            transform = CGAffineTransformTranslate(transform, self.size.width, self.size.height);
            transform = CGAffineTransformRotate(transform, M_PI);
            break;
            
        case UIImageOrientationLeft:
        case UIImageOrientationLeftMirrored:
            transform = CGAffineTransformTranslate(transform, self.size.width, 0);
            transform = CGAffineTransformRotate(transform, M_PI_2);
            break;
            
        case UIImageOrientationRight:
        case UIImageOrientationRightMirrored:
            transform = CGAffineTransformTranslate(transform, 0, self.size.height);
            transform = CGAffineTransformRotate(transform, -M_PI_2);
            break;
        case UIImageOrientationUp:
        case UIImageOrientationUpMirrored:
            break;
    }
    
    switch (self.imageOrientation) {
        case UIImageOrientationUpMirrored:
        case UIImageOrientationDownMirrored:
            transform = CGAffineTransformTranslate(transform, self.size.width, 0);
            transform = CGAffineTransformScale(transform, -1, 1);
            break;
            
        case UIImageOrientationLeftMirrored:
        case UIImageOrientationRightMirrored:
            transform = CGAffineTransformTranslate(transform, self.size.height, 0);
            transform = CGAffineTransformScale(transform, -1, 1);
            break;
        case UIImageOrientationUp:
        case UIImageOrientationDown:
        case UIImageOrientationLeft:
        case UIImageOrientationRight:
            break;
    }
    
    // Now we draw the underlying CGImage into a new context, applying the transform
    // calculated above.
    CGContextRef ctx = CGBitmapContextCreate(NULL, self.size.width, self.size.height,
                                             CGImageGetBitsPerComponent(self.CGImage), 0,
                                             CGImageGetColorSpace(self.CGImage),
                                             CGImageGetBitmapInfo(self.CGImage));
    CGContextConcatCTM(ctx, transform);
    switch (self.imageOrientation) {
        case UIImageOrientationLeft:
        case UIImageOrientationLeftMirrored:
        case UIImageOrientationRight:
        case UIImageOrientationRightMirrored:
            CGContextDrawImage(ctx, CGRectMake(0, 0, self.size.height, self.size.width), self.CGImage);
            break;
            
        default:
            CGContextDrawImage(ctx, CGRectMake(0, 0, self.size.width, self.size.height), self.CGImage);
            break;
    }
    
    // And now we just create a new UIImage from the drawing context.
    CGImageRef cgimg = CGBitmapContextCreateImage(ctx);
    UIImage *img = [UIImage imageWithCGImage:cgimg];
    CGContextRelease(ctx);
    CGImageRelease(cgimg);
    
    return img;
}

-(NSData *) encodeDataWithGIF {
    CGImageRef imageRef = self.CGImage;
    if (!imageRef) {
        return nil;
    }
    
    NSMutableData *imageData = [NSMutableData data];
    CFStringRef imageUTType = kUTTypeGIF;
    NSArray<TOImageFrame *> *frames = [self frames];
    
    // Create an image destination. Animated Image does not support EXIF image orientation TODO
    // The `CGImageDestinationCreateWithData` will log a warning when count is 0, use 1 instead.
    CGImageDestinationRef imageDestination = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)imageData, imageUTType, frames.count ?: 1, NULL);
    
    if (!imageDestination) {
        // Handle failure.
        return nil;
    }
    
    NSMutableDictionary *properties = [NSMutableDictionary dictionary];
    // Encoding Options
    double compressionQuality = 1;
    CGSize maxPixelSize = CGSizeZero;

    properties[(__bridge NSString *)kCGImageDestinationLossyCompressionQuality] = @(compressionQuality);
    
    NSUInteger pixelWidth = CGImageGetWidth(imageRef);
    NSUInteger pixelHeight = CGImageGetHeight(imageRef);
    CGFloat finalPixelSize = 0;
    if (maxPixelSize.width > 0 && maxPixelSize.height > 0 && pixelWidth > maxPixelSize.width && pixelHeight > maxPixelSize.height) {
        CGFloat pixelRatio = pixelWidth / pixelHeight;
        CGFloat maxPixelSizeRatio = maxPixelSize.width / maxPixelSize.height;
        if (pixelRatio > maxPixelSizeRatio) {
            finalPixelSize = maxPixelSize.width;
        } else {
            finalPixelSize = maxPixelSize.height;
        }
        properties[(__bridge NSString *)kCGImageDestinationImageMaxPixelSize] = @(finalPixelSize);
    }
    
    // for animated images, use loop count of 0
    NSDictionary *containerProperties =@{
        (__bridge id)kCGImagePropertyGIFDictionary: @{
            (__bridge id)kCGImagePropertyGIFLoopCount: @0, // 0 means loop forever
        }
    };
    // container level properties (applies for `CGImageDestinationSetProperties`, not individual frames)
    CGImageDestinationSetProperties(imageDestination, (__bridge CFDictionaryRef)containerProperties);
    
    for (size_t i = 0; i < frames.count; i++) {
        TOImageFrame *frame = frames[i];
        NSTimeInterval frameDuration = frame.duration;
        CGImageRef frameImageRef = frame.image.CGImage;
        properties[(__bridge id)kCGImagePropertyGIFDictionary] =  @{
            (__bridge id)kCGImagePropertyGIFDelayTime: @(frameDuration), // a float (not double!) in seconds, rounded to centiseconds in the GIF data
        };
        CGImageDestinationAddImage(imageDestination, frameImageRef, (__bridge CFDictionaryRef)properties);
    }
    
    // Finalize the destination.
    if (CGImageDestinationFinalize(imageDestination) == NO) {
        // Handle failure.
        imageData = nil;
    }
    
    CFRelease(imageDestination);
    
    return [imageData copy];
}

@end
