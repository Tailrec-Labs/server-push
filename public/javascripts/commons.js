function escapeHTML(html){
  return $('<div/>').text(html).html()
}

const alphanumericChars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
  
function randomString(length) {
  var result = '';
  for (var i = length; i > 0; --i) result += alphanumericChars[Math.round(Math.random() * (alphanumericChars.length - 1))];
  return result;
}