package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.entity.PlatformMailFetchWatermark;
import io.softa.starter.message.mail.service.PlatformMailFetchWatermarkService;

/** Implementation of {@link PlatformMailFetchWatermarkService}: the platform's receive servers' watermarks. */
@Service
public class PlatformMailFetchWatermarkServiceImpl
        extends AbstractMailFetchWatermarkServiceImpl<PlatformMailFetchWatermark>
        implements PlatformMailFetchWatermarkService {

    @Override
    protected PlatformMailFetchWatermark newWatermark() {
        return new PlatformMailFetchWatermark();
    }
}
