package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ImportInputLimitsTest {
    private ImportInputLimits limits(int[] values) {
        return new ImportInputLimits(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9],values[10]);
    }
    private int[] defaults() { return new int[]{2097152,262144,3145728,500,32,65536,256,30000,10000,5000,4}; }
    @Test void rejectsNonpositiveAndAboveCeilingConfiguration() {
        for (int index=0;index<11;index++) {
            int[] invalid=defaults(); invalid[index]++;
            assertThatThrownBy(() -> limits(invalid)).isInstanceOf(IllegalArgumentException.class);
            int[] nonpositive=defaults(); nonpositive[index]=0;
            assertThatThrownBy(() -> limits(nonpositive)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void bindsTightenedLimitsAndFailsStartupForInvalidOverrides() {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(ImportInputLimits.class);
        runner.withPropertyValues("campus.imports.data-records=2", "campus.imports.process-preview-count=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ImportInputLimits.class).dataRecords()).isEqualTo(2);
                    assertThat(context.getBean(ImportInputLimits.class).processPreviewCount()).isEqualTo(1);
                });
        runner.withPropertyValues("campus.imports.preview-work-ms=30001").run(context -> assertThat(context).hasFailed());
    }
    @Test void parserHonorsTighterFileRecordColumnAndScalarLimits() {
        int[] values=defaults(); values[0]=16; values[3]=1; values[4]=1; values[5]=4; values[6]=4;
        var parser=new CsvImportParser(limits(values));
        assertThat(parser.parse("h\n1234".getBytes(StandardCharsets.UTF_8)).records()).hasSize(1);
        for(String csv:new String[]{"h\na\nb", "a,b\nx,y", "h\n12345", "12345\nx", "h\n"+"a".repeat(15)})
            assertThatThrownBy(() -> parser.parse(csv.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
    }
    @Test void workPermitsAndRequestDeadlineUseTightenedValues() throws Exception {
        int[] values=defaults(); values[7]=1; values[10]=1;
        var configured=limits(values);
        var limiter=new ImportWorkLimiter(configured);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID(); limiter.acquire(first);
        assertThatThrownBy(() -> limiter.acquire(second)).isInstanceOf(ApiException.class);
        limiter.release(first); assertThatCode(() -> limiter.acquire(second)).doesNotThrowAnyException();
        var request=new MockHttpServletRequest(); ImportUploadPolicy.begin(request,configured);
        Thread.sleep(5);
        assertThatThrownBy(() -> ImportUploadPolicy.checkDeadline(request)).isInstanceOf(ApiException.class);
    }
    @Test void transportHonorsTighterAggregateLimitWithoutConsumingContent() {
        int[] values=defaults(); values[2]=20;
        var request=new MockHttpServletRequest("POST","/api/application-imports/preview");
        request.setProtocol("HTTP/1.1"); request.setContentType("multipart/form-data; boundary=b");
        request.addHeader("Content-Length",21);
        assertThatThrownBy(() -> ImportUploadPolicy.checkTransport(request,limits(values))).isInstanceOf(ApiException.class);
    }
    @Test void readerHonorsIndependentTighterFileAndOptionsCaps() {
        int[] values=defaults(); values[0]=4; values[1]=6;
        var reader=new ImportMultipartReader(limits(values));
        assertThat(reader.read(upload("1234","123456")).optionsJson()).isEqualTo("123456");
        assertThatThrownBy(() -> reader.read(upload("12345","{}"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reader.read(upload("x","1234567"))).isInstanceOf(ApiException.class);
    }
    private MockHttpServletRequest upload(String file,String options) {
        String body="--b\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n"+file
                +"\r\n--b\r\nContent-Disposition: form-data; name=\"options\"\r\n\r\n"+options+"\r\n--b--\r\n";
        var request=new MockHttpServletRequest("POST","/api/application-imports/preview");
        request.setProtocol("HTTP/1.1"); request.setContentType("multipart/form-data; boundary=b");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); request.addHeader("Content-Length",request.getContentLength());
        return request;
    }
}
