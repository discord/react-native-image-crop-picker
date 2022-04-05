//
//  Compression.m
//  imageCropPicker
//
//  Created by Ivan Pusic on 12/24/16.
//  Copyright © 2016 Ivan Pusic. All rights reserved.
//

#import "Compression.h"
#import <TOCropViewController/TOImageFrame.h>
#import <TOCropViewController/UIImage+Animated.h>
#import "UIImage+Extension.h"

@implementation Compression

- (instancetype)init {
    NSMutableDictionary *dic = [[NSMutableDictionary alloc] initWithDictionary:@{
                                                                                 @"640x480": AVAssetExportPreset640x480,
                                                                                 @"960x540": AVAssetExportPreset960x540,
                                                                                 @"1280x720": AVAssetExportPreset1280x720,
                                                                                 @"1920x1080": AVAssetExportPreset1920x1080,
                                                                                 @"LowQuality": AVAssetExportPresetLowQuality,
                                                                                 @"MediumQuality": AVAssetExportPresetMediumQuality,
                                                                                 @"HighestQuality": AVAssetExportPresetHighestQuality,
                                                                                 @"Passthrough": AVAssetExportPresetPassthrough,
                                                                                 }];
    
    if (@available(iOS 9.0, *)) {
        [dic addEntriesFromDictionary:@{@"3840x2160": AVAssetExportPreset3840x2160}];
    } else {
        // Fallback on earlier versions
    }
    
    self.exportPresets = dic;
    
    return self;
}

- (ImageResult*) compressImageDimensions:(UIImage*)image
                   compressImageMaxWidth:(CGFloat)maxWidth
                  compressImageMaxHeight:(CGFloat)maxHeight
                              intoResult:(ImageResult*)result {
    
    CGFloat oldWidth = image.size.width;
    CGFloat oldHeight = image.size.height;
    
    int newWidth = 0;
    int newHeight = 0;
    
    if (maxWidth < maxHeight) {
        newWidth = maxWidth;
        newHeight = (oldHeight / oldWidth) * newWidth;
    } else {
        newHeight = maxHeight;
        newWidth = (oldWidth / oldHeight) * newHeight;
    }
    CGSize newSize = CGSizeMake(newWidth, newHeight);
    
    UIImage *resizedImage;
    if (image.images) {
        NSArray<TOImageFrame *> * frames = [image frames];
        NSMutableArray<TOImageFrame*> * resizedFrames = [NSMutableArray arrayWithCapacity:frames.count];

        [frames enumerateObjectsUsingBlock:^(TOImageFrame * _Nonnull imageFrame, NSUInteger idx, BOOL * _Nonnull stop) {
            UIGraphicsBeginImageContext(newSize);
            [imageFrame.image drawInRect:CGRectMake(0, 0, newSize.width, newSize.height)];
            UIImage *resized = UIGraphicsGetImageFromCurrentImageContext();
            UIGraphicsEndImageContext();
            TOImageFrame* resizedImageFrame = [TOImageFrame frameWithImage:resized duration:imageFrame.duration];
            [resizedFrames addObject:resizedImageFrame];
        }];
    } else {
        UIGraphicsBeginImageContext(newSize);
        [image drawInRect:CGRectMake(0, 0, newSize.width, newSize.height)];
        resizedImage = UIGraphicsGetImageFromCurrentImageContext();
        UIGraphicsEndImageContext();
    }

    result.width = [NSNumber numberWithFloat:newWidth];
    result.height = [NSNumber numberWithFloat:newHeight];
    result.image = resizedImage;
    return result;
}

- (ImageResult*) compressImage:(UIImage*)image
                   withOptions:(NSDictionary*)options {
    
    ImageResult *result = [[ImageResult alloc] init];
    result.width = @(image.size.width);
    result.height = @(image.size.height);
    result.image = image;
    
    if (!image.images) {
        result.mime = @"image/jpeg";
    } else {
        result.mime = @"image/gif";
    }
    
    NSNumber *compressImageMaxWidth = [options valueForKey:@"compressImageMaxWidth"];
    NSNumber *compressImageMaxHeight = [options valueForKey:@"compressImageMaxHeight"];
    
    // determine if it is necessary to resize image
    BOOL shouldResizeWidth = (compressImageMaxWidth != nil && [compressImageMaxWidth floatValue] < image.size.width);
    BOOL shouldResizeHeight = (compressImageMaxHeight != nil && [compressImageMaxHeight floatValue] < image.size.height);
    
    if (shouldResizeWidth || shouldResizeHeight) {
        CGFloat maxWidth = compressImageMaxWidth != nil ? [compressImageMaxWidth floatValue] : image.size.width;
        CGFloat maxHeight = compressImageMaxHeight != nil ? [compressImageMaxHeight floatValue] : image.size.height;
        
        [self compressImageDimensions:image
                compressImageMaxWidth:maxWidth
               compressImageMaxHeight:maxHeight
                           intoResult:result];
    }
    
    // parse desired image quality
    NSNumber *compressQuality = [options valueForKey:@"compressImageQuality"];
    if (compressQuality == nil) {
        compressQuality = [NSNumber numberWithFloat:0.8];
    }

    // compression is only supported for non animated images for now
    if (!image.images) {
        // convert image to jpeg representation
        result.data = UIImageJPEGRepresentation(result.image, [compressQuality floatValue]);
    } else {
        result.data = [result.image encodeDataWithGIF];
    }

    return result;
}

- (void)compressVideo:(NSURL*)inputURL
            outputURL:(NSURL*)outputURL
          withOptions:(NSDictionary*)options
              handler:(void (^)(AVAssetExportSession*))handler {
    
    NSString *presetKey = [options valueForKey:@"compressVideoPreset"];
    if (presetKey == nil) {
        presetKey = @"MediumQuality";
    }
    
    NSString *preset = [self.exportPresets valueForKey:presetKey];
    if (preset == nil) {
        preset = AVAssetExportPresetMediumQuality;
    }
    
    [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];
    AVURLAsset *asset = [AVURLAsset URLAssetWithURL:inputURL options:nil];
    AVAssetExportSession *exportSession = [[AVAssetExportSession alloc] initWithAsset:asset presetName:preset];
    exportSession.shouldOptimizeForNetworkUse = YES;
    exportSession.outputURL = outputURL;
    exportSession.outputFileType = AVFileTypeMPEG4;
    
    [exportSession exportAsynchronouslyWithCompletionHandler:^(void) {
        handler(exportSession);
    }];
}

@end
