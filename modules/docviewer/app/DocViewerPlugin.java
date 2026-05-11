import play.Play;
import play.PlayPlugin;
import play.libs.IO;
import play.libs.MimeTypes;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.vfs.VirtualFile;

import java.io.File;

public class DocViewerPlugin extends PlayPlugin {

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if ("/@docs/api".equals(request.path) || "/@docs/api/".equals(request.path)) {
            response.status = 302;
            response.setHeader("Location", "/@docs/api/index.html");
            return true;
        }
        if (request.path.startsWith("/@docs/api/")) {
            if (request.path.matches("/@docs/api/-[a-z]+/.*")) {
                String module = request.path.substring(request.path.indexOf("-") + 1);
                module = module.substring(0, module.indexOf("/"));
                VirtualFile f = Play.modules.get(module).child("documentation/api/" + request.path.substring(13 + module.length()));
                if (f.exists()) {
                    response.contentType = MimeTypes.getMimeType(f.getName());
                    response.out.write(f.content());
                }
                return true;
            }
            File f = new File(Play.frameworkPath, "documentation/api/" + request.path.substring(11));
            if (f.exists()) {
                response.contentType = MimeTypes.getMimeType(f.getName());
                response.out.write(IO.readContent(f));
            }
            return true;
        }
        return false;
    }

    @Override
    public void onRoutesLoaded() {
        Router.prependRoute("GET", "/@docs/?", "PlayDocumentation.index");
        Router.prependRoute("GET", "/@docs/{id}", "PlayDocumentation.page");
        Router.prependRoute("GET", "/@docs/home", "PlayDocumentation.index");
        Router.prependRoute("GET", "/@docs/{docLang}/{id}", "PlayDocumentation.page");
        Router.prependRoute("GET", "/@docs/images/{name}", "PlayDocumentation.image");
        Router.prependRoute("GET", "/@docs/files/{name}", "PlayDocumentation.file");
        Router.prependRoute("GET", "/@docs/{docLang}/images/{name}", "PlayDocumentation.image");
        Router.prependRoute("GET", "/@docs/{docLang}/files/{name}", "PlayDocumentation.file");
        Router.prependRoute("GET", "/@docs/{docLang}/modules/{module}/{id}", "PlayDocumentation.page");
        Router.prependRoute("GET", "/@docs/{docLang}/releases/{id}", "PlayDocumentation.releases");
        Router.prependRoute("GET", "/@docs/{docLang}/releases/{version}/{id}", "PlayDocumentation.releases");
        Router.prependRoute("GET", "/@docs/modules/{module}/images/{name}", "PlayDocumentation.image");
        Router.prependRoute("GET", "/@docs/modules/{module}/files/{name}", "PlayDocumentation.file");
        Router.prependRoute("GET", "/@docs/cheatsheet/{category}", "PlayDocumentation.cheatSheet");
        Router.prependRoute("GET", "/@docs/{docLang}/cheatsheet/{category}", "PlayDocumentation.cheatSheet");
        Router.prependRoute("GET", "/@projectdocs/?", "ProjectDocumentation.index");
        Router.prependRoute("GET", "/@projectdocs/{id}", "ProjectDocumentation.page");
        Router.prependRoute("GET", "/@projectdocs/images/{name}", "ProjectDocumentation.image");
        Router.prependRoute("GET", "/@projectdocs/files/{name}", "ProjectDocumentation.file");
    }

}
