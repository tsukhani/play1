document.addEventListener('DOMContentLoaded', function() {

    // A link to the given href whose text is the given text. Built as elements, not as HTML, so a
    // heading's text stays text: a heading such as "Option<T>" must not be parsed as markup (PF-253).
    function link(href, text) {
        var a = document.createElement('a');
        a.setAttribute('href', href);
        a.textContent = text;
        return a;
    }

    // Construct an OL of this page's H2/H3
    var navigation = document.createElement('ol');
    navigation.className = 'navigation';
    var h2Item = null;  // the LI of the last H2, which the H3s after it are nested in
    var nested = null;  // the UL of the H3s since the last H2

    var elements = document.querySelectorAll('#pageContent h2, #pageContent h3, #pageContent > div > ol > li > a');
    elements.forEach(function(el) {

        // In each heading, construct an in-page link from its id, or the nested a[name]
        var anchorEl = el.querySelector('a[name]');
        var anchor = anchorEl ? anchorEl.getAttribute('name') : el.getAttribute('id');
        var href = el.tagName === 'A' ? el.getAttribute('href') : '#' + anchor;

        if (el.tagName === 'H2') {
            // An LI for this H2; the H3s that follow it go into a new nested UL.
            h2Item = document.createElement('li');
            h2Item.appendChild(link(href, el.textContent));
            navigation.appendChild(h2Item);
            nested = null;
        }

        // Output a nested LI for this H3.
        var linkHasNestedList = (el.tagName === 'A' && el.closest('li') && el.closest('li').querySelector('li'));
        if (el.tagName === 'H3' || linkHasNestedList || el.classList.contains('navigation')) {

            // Start a new nested UL for the first H3.
            if (nested === null) {
                nested = document.createElement('ul');
                (h2Item || navigation).appendChild(nested);
            }

            var item = document.createElement('li');
            item.appendChild(link(href, el.textContent));
            nested.appendChild(item);
        }
    });

    var toc = document.getElementById('toc');
    if (toc) {
        toc.textContent = '';
        toc.appendChild(navigation);
    }

    // Next link
    var nextLinkEl = document.querySelector('.next a');
    if (nextLinkEl) {
        var gotoc = document.getElementById('gotoc');
        if (gotoc) {
            var li = document.createElement('li');
            li.appendChild(link(nextLinkEl.getAttribute('href'), 'Next: ' + nextLinkEl.textContent));
            gotoc.parentNode.insertBefore(li, gotoc.nextSibling);
        }
    }
});
