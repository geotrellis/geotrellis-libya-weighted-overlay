output "service_elb_ip" {
  value = "${aws_elb.libya.dns_name}"
}
