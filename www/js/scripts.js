
function httpGet() {
    var req = new XMLHttpRequest();
    req.open('GET', 'http://localhost:8080/overview', false);
    req.send(null);
    if(req.status == 200) {
        return req.responseText ;
    }
}

var items = JSON.parse(httpGet());

var body = document.getElementsByTagName('body')[0];
var table = document.createElement('table');
var tableBody = document.createElement('tbody');

table.appendChild(tableBody);
body.appendChild(table);

function build_col(contents) {
    var td = document.createElement('td');
    td.appendChild(contents);
    return td;
}

function build_bar(pos, max) {
    var bar = document.createElement("PROGRESS");
    bar.setAttribute("value", pos);
    bar.setAttribute("max", max);
    bar.innerHTMP = 'ads';
    return bar;
}


var th = table.createTHead();
th.appendChild(build_col(document.createTextNode('ID')));
th.appendChild(build_col(document.createTextNode('Cluster Name')));
th.appendChild(build_col(document.createTextNode('Status')));
th.appendChild(build_col(document.createTextNode('Progress')));

for (var clusterName in items) {
    var tr = document.createElement('tr');
    tr.appendChild(build_col(document.createTextNode(items[clusterName]['runId'])));
    tr.appendChild(build_col(document.createTextNode(items[clusterName]['cluster'])));
    tr.appendChild(build_col(document.createTextNode(items[clusterName]['status'])));
    tr.appendChild(build_bar(items[clusterName]["doneSegments"], items[clusterName]["totalSegments"]));
    tableBody.appendChild(tr);
}

setInterval(function() {window.location.reload();}, 15000);
