package com.x.file.assemble.control.jaxrs.attachment2;

import com.x.base.core.container.EntityManagerContainer;
import com.x.base.core.container.factory.EntityManagerContainerFactory;
import com.x.base.core.project.cache.ApplicationCache;
import com.x.base.core.project.config.StorageMapping;
import com.x.base.core.project.exception.ExceptionWhen;
import com.x.base.core.project.http.ActionResult;
import com.x.base.core.project.http.EffectivePerson;
import com.x.base.core.project.jaxrs.WoFile;
import com.x.base.core.project.logger.Logger;
import com.x.base.core.project.logger.LoggerFactory;
import com.x.file.assemble.control.ThisApplication;
import com.x.file.core.entity.open.OriginFile;
import com.x.file.core.entity.personal.Attachment2;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

class ActionDownloadImageWidthHeight extends BaseAction {

	private static Logger logger = LoggerFactory.getLogger( ActionDownloadImageWidthHeight.class );

	private Ehcache cache = ApplicationCache.instance().getCache(ActionDownloadImageWidthHeight.class);

	ActionResult<Wo> execute(EffectivePerson effectivePerson, String id, Integer width, Integer height)
			throws Exception {
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			ActionResult<Wo> result = new ActionResult<>();
			Attachment2 attachment = emc.find(id, Attachment2.class, ExceptionWhen.not_found);
			/* 判断文件的当前用户是否是管理员或者文件创建者 或者当前用户在分享或者共同编辑中 */
			if (effectivePerson.isNotManager() && effectivePerson.isNotPerson(attachment.getPerson())) {
				throw new Exception("person{name:" + effectivePerson.getDistinguishedName() + "} access attachment{id:"
						+ id + "} denied.");
			}
			if (!ArrayUtils.contains(IMAGE_EXTENSIONS, attachment.getExtension())) {
				throw new Exception("attachment not image file.");
			}
			if (width < 0 || width > 5000) {
				throw new Exception("invalid width:" + width + ".");
			}
			if (height < 0 || height > 5000) {
				throw new Exception("invalid height:" + height + ".");
			}
			OriginFile originFile = emc.find(attachment.getOriginFile(),OriginFile.class);
			if (null == originFile) {
				throw new ExceptionAttachmentNotExist(id,attachment.getOriginFile());
			}
			Wo wo = null;
			String cacheKey = ApplicationCache.concreteCacheKey(this.getClass(), id+width+height);
			Element element = cache.get(cacheKey);
			if ((null != element) && (null != element.getObjectValue())) {
				wo = (Wo) element.getObjectValue();
				result.setData(wo);
			} else {
				StorageMapping mapping = ThisApplication.context().storageMappings().get(OriginFile.class,
						originFile.getStorage());
				try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
					originFile.readContent(mapping, output);
					try (ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray())) {
						BufferedImage src = ImageIO.read(input);
						int scalrWidth = (width == 0) ? src.getWidth() : width;
						int scalrHeight = (height == 0) ? src.getHeight() : height;
						Scalr.Mode mode = Scalr.Mode.FIT_TO_WIDTH;
						if(src.getWidth()>src.getHeight()){
							mode = Scalr.Mode.FIT_TO_HEIGHT;
						}
						BufferedImage scalrImage = Scalr.resize(src,Scalr.Method.SPEED, mode, NumberUtils.min(scalrWidth, src.getWidth()),
								NumberUtils.min(scalrHeight, src.getHeight()));
						try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
							ImageIO.write(scalrImage, "png", baos);
							byte[] bs = baos.toByteArray();

							wo = new Wo(bs, this.contentType(false, attachment.getName()),
									this.contentDisposition(false, attachment.getName()));

							cache.put(new Element(cacheKey, wo));
							result.setData(wo);
						}
					}
				}catch (Exception e){
					if(e.getMessage().indexOf("existed") > -1){
						logger.warn("原始附件{}-{}不存在，删除记录！", originFile.getId(), originFile.getName());
						emc.beginTransaction(OriginFile.class);
						emc.delete(OriginFile.class, originFile.getId());
						emc.commit();
					}
					throw e;
				}
			}

			return result;
		}
	}

	public static class Wo extends WoFile {

		public Wo(byte[] bytes, String contentType, String contentDisposition) {
			super(bytes, contentType, contentDisposition);
		}

	}
}